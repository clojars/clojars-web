(ns clojars.oauth.github
  (:require
   [clojars.oauth.service :as oauth-service]
   [clojars.remote-service :as remote-service :refer [defendpoint]])
  (:import
   (com.github.scribejava.apis
    GitHubApi)))

(defendpoint get-emails
  [_client token]
  {:method :get
   :url "https://api.github.com/user/emails"
   :oauth-token token})

(defendpoint get-user
  [_client token]
  {:method :get
   :url "https://api.github.com/user/user"
   :oauth-token token})

(defrecord GitHubService [service]
  oauth-service/OauthService

  (authorization-url [_]
    (.getAuthorizationUrl service {"scope" "user:email"}))

  (access-token [_ code]
    (.getAccessToken (.getAccessToken service code)))

  (provider-name [_]
    "GitHub"))

(defmethod oauth-service/get-user-details "GitHub"
  [_ http-client token]
  (let [emails (get-emails http-client token)
        verified-emails (into []
                              (comp (filter :verified)
                                    (map :email))
                              emails)]
    {:emails verified-emails
     :login  (:login (get-user http-client token))}))

(defn- build-github-service [api-key api-secret callback-uri]
  (oauth-service/build-oauth-service
   api-key
   api-secret
   callback-uri
   (GitHubApi/instance)))

(defn new-github-service [api-key api-secret callback-uri]
  (->GitHubService (build-github-service api-key api-secret callback-uri)))
