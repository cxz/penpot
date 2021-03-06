;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020-2021 UXBOX Labs SL

(ns app.http.auth.github
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.config :as cfg]
   [app.util.http :as http]
   [app.util.time :as dt]
   [clojure.data.json :as json]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]
   [lambdaisland.uri :as u]))

(def base-github-uri
  (u/uri "https://github.com"))

(def base-api-github-uri
  (u/uri "https://api.github.com"))

(def authorize-uri
  (assoc base-github-uri :path "/login/oauth/authorize"))

(def token-url
  (assoc base-github-uri :path "/login/oauth/access_token"))

(def user-info-url
  (assoc base-api-github-uri :path "/user"))

(def scope "user:email")

(defn- build-redirect-url
  [cfg]
  (let [public (u/uri (:public-uri cfg))]
    (str (assoc public :path "/api/oauth/github/callback"))))

(defn- get-access-token
  [cfg state code]
  (try
    (let [params {:client_id (:client-id cfg)
                  :client_secret (:client-secret cfg)
                  :code code
                  :state state
                  :redirect_uri (build-redirect-url cfg)}
          req    {:method :post
                  :headers {"content-type" "application/x-www-form-urlencoded"
                            "accept" "application/json"}
                  :uri  (str token-url)
                  :timeout 6000
                  :body (u/map->query-string params)}
          res    (http/send! req)]

      (when (= 200 (:status res))
        (-> (json/read-str (:body res))
            (get "access_token"))))

    (catch Exception e
      (log/error e "unexpected error on get-access-token")
      nil)))

(defn- get-user-info
  [token]
  (try
    (let [req {:uri (str user-info-url)
               :headers {"authorization" (str "token " token)}
               :timeout 6000
               :method :get}
          res (http/send! req)]
      (when (= 200 (:status res))
        (let [data (json/read-str (:body res))]
          {:email (get data "email")
           :fullname (get data "name")})))
    (catch Exception e
      (log/error e "unexpected exception on get-user-info")
      nil)))

(defn auth
  [{:keys [tokens] :as cfg} _request]
  (let [state  (tokens :generate {:iss :github-oauth :exp (dt/in-future "15m")})
        params {:client_id (:client-id cfg/config)
                :redirect_uri (build-redirect-url cfg)
                :state state
                :scope scope}
        query (u/map->query-string params)
        uri   (-> authorize-uri
                  (assoc :query query))]
    {:status 200
     :body {:redirect-uri (str uri)}}))

(defn callback
  [{:keys [tokens rpc session] :as cfg} request]
  (try
    (let [state (get-in request [:params :state])
          _     (tokens :verify {:token state :iss :github-oauth})
          info  (some->> (get-in request [:params :code])
                         (get-access-token cfg state)
                         (get-user-info))

          _     (when-not info
                  (ex/raise :type :internal
                            :code :unable-to-auth))

          method-fn (get-in rpc [:methods :mutation :login-or-register])
          profile   (method-fn {:email (:email info)
                                :backend "github"
                                :fullname (:fullname info)})
          token     (tokens :generate
                            {:iss :auth
                             :exp (dt/in-future "15m")
                             :profile-id (:id profile)})
          uri       (-> (u/uri (:public-uri cfg/config))
                        (assoc :path "/#/auth/verify-token")
                        (assoc :query (u/map->query-string {:token token})))
          sxf       ((:create session) (:id profile))
          rsp       {:status 302 :headers {"location" (str uri)} :body ""}]
      (sxf request rsp))
    (catch Exception _e
      (let [uri (-> (u/uri (:public-uri cfg))
                    (assoc :path "/#/auth/login")
                    (assoc :query (u/map->query-string {:error "unable-to-auth"})))]
        {:status 302
         :headers {"location" (str uri)}
         :body ""}))))

;; --- ENTRY POINT

(s/def ::client-id ::us/not-empty-string)
(s/def ::client-secret ::us/not-empty-string)
(s/def ::public-uri ::us/not-empty-string)
(s/def ::session map?)
(s/def ::tokens fn?)

(defmethod ig/pre-init-spec :app.http.auth/github [_]
  (s/keys :req-un [::public-uri
                   ::session
                   ::tokens]
          :opt-un [::client-id
                   ::client-secret]))

(defn- default-handler
  [_]
  (ex/raise :type :not-found))

(defmethod ig/init-key :app.http.auth/github
  [_ cfg]
  (if (and (:client-id cfg)
           (:client-secret cfg))
    {:auth-handler #(auth cfg %)
     :callback-handler #(callback cfg %)}
    {:auth-handler default-handler
     :callback-handler default-handler}))

