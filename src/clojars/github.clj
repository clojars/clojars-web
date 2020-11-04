(ns clojars.github
  (:require [clj-http.client :as http]
            [cheshire.core :as json])

  (:import [com.github.scribejava.core.builder ServiceBuilder]
           [com.github.scribejava.apis GitHubApi]))

(defprotocol SocialService
  (authorization-url [service])
  (access-token [service code])
  (get-verified-emails [service token]))

(defrecord GitHubService [service]
  SocialService

  (authorization-url [this]
    (.getAuthorizationUrl service {"scope" "user:email"}))

  (access-token [this code]
    (.getAccessToken (.getAccessToken service code)))

  (get-verified-emails [this token]
    (let [emails (-> (http/get "https://api.github.com/user/emails" {:oauth-token token})
                     :body
                     (json/parse-string true))]
      (->> emails (filter :verified) (mapv :email)))))

(defn- build-github-service [api-key api-secret callback-uri]
  (-> (ServiceBuilder. api-key)
      (.apiSecret api-secret)
      (.callback callback-uri)
      (.build (GitHubApi/instance))))

(defn new-github-service [api-key api-secret callback-uri]
  (map->GitHubService {:service (build-github-service api-key api-secret callback-uri)}))

(defrecord MockGitHubService [config]
  SocialService

  (authorization-url [this]
    (:authorize-uri config))

  (access-token [this code]
    (:access-token config))

  (get-verified-emails [this token]
    (mapv :email (filter :verified (:email config)))))

(defn new-mock-github-service [config]
  (map->MockGitHubService {:config (merge {:authorize-uri "https://github.com/login/oauth/authorize"}
                                          config)}))
