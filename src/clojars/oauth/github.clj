(ns clojars.oauth.github
  (:require
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clojars.oauth.service :as oauth-service])
  (:import
   (com.github.scribejava.apis
    GitHubApi)
   (com.github.scribejava.core.builder
    ServiceBuilder)))

(defn- get+parse-body [token url]
  (-> (http/get url {:oauth-token token})
      :body
      (json/parse-string true)))

(defrecord GitHubService [service]
  oauth-service/OauthService

  (authorization-url [_]
    (.getAuthorizationUrl service {"scope" "user:email"}))

  (access-token [_ code]
    (.getAccessToken (.getAccessToken service code)))

  (get-user-details [_ token]
    {:emails (get+parse-body token "https://api.github.com/user/emails")
     :login  (:login (get+parse-body token "https://api.github.com/user"))})

  (provider-name [_]
    :github))

(defn- build-github-service [api-key api-secret callback-uri]
  (-> (ServiceBuilder. api-key)
      (.apiSecret api-secret)
      (.callback callback-uri)
      (.build (GitHubApi/instance))))

(defn new-github-service [api-key api-secret callback-uri]
  (map->GitHubService {:service (build-github-service api-key api-secret callback-uri)}))
