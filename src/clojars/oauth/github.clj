(ns clojars.oauth.github
  (:require
   [clojars.oauth.service :as oauth-service]
   [clojars.remote-service :as remote-service :refer [defendpoint]])
  (:import
   (com.github.scribejava.apis
    GitHubApi)
   (com.github.scribejava.core.builder
    ServiceBuilder)))

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

(defrecord GitHubService [service http-service]
  oauth-service/OauthService

  (authorization-url [_]
    (.getAuthorizationUrl service {"scope" "user:email"}))

  (access-token [_ code]
    (.getAccessToken (.getAccessToken service code)))

  (provider-name [_]
    :github))

(defmethod oauth-service/get-user-details :github
  [_ http-client token]
  (let [emails (get-emails http-client token)
        verified-emails (into []
                              (comp (filter :verified)
                                    (map :email))
                              emails)]
    {:emails verified-emails
     :login  (:login (get-user http-client token))}))

(defn- build-github-service [api-key api-secret callback-uri]
  (-> (ServiceBuilder. api-key)
      (.apiSecret api-secret)
      (.callback callback-uri)
      (.build (GitHubApi/instance))))

(defn new-github-service [api-key api-secret callback-uri]
  (map->GitHubService {:service (build-github-service api-key api-secret callback-uri)}))
