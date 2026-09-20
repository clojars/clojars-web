(ns clojars.oauth.gitlab
  (:require
   [clojars.oauth.service :as oauth-service]
   [clojars.remote-service :refer [defendpoint]])
  (:import
   (com.github.scribejava.core.builder.api
    DefaultApi20)
   (com.github.scribejava.core.oauth
    OAuth20Service)))

(set! *warn-on-reflection* true)

(defrecord GitlabService [^OAuth20Service service]
  oauth-service/OauthService

  (authorization-url [_]
    (.getAuthorizationUrl service {"scope" "read_user"}))

  (access-token [_ code]
    (.getAccessToken (.getAccessToken service ^String code)))

  (provider-name [_]
    "GitLab"))

;; https://docs.gitlab.com/api/users/#retrieve-the-current-user
(defendpoint get-user
  [_client token]
  {:method :get
   :url "https://gitlab.com/api/v4/user"
   :oauth-token token})

;; https://docs.gitlab.com/api/user_email_addresses/#list-all-email-addresses
(defendpoint get-user-emails
  [_client token]
  {:method :get
   :url "https://gitlab.com/api/v4/user/emails"
   :oauth-token token})

(defmethod oauth-service/get-user-details "GitLab"
  [_ http-client token]
  (let [{:keys [username]} (get-user http-client token)
        confirmed-emails (into []
                               (comp
                                (filter :confirmed_at)
                                (map :email))
                               (get-user-emails http-client token))]
    {:emails confirmed-emails
     :login  username}))

(defn- gitlab-instance []
  (proxy [DefaultApi20] []
    (getAccessTokenEndpoint []
      "https://gitlab.com/oauth/token")
    (getAuthorizationBaseUrl []
      "https://gitlab.com/oauth/authorize")))

(defn- build-gitlab-service [api-key api-secret callback-uri]
  (oauth-service/build-oauth-service
   api-key
   api-secret
   callback-uri
   (gitlab-instance)))

(defn new-gitlab-service [api-key api-secret callback-uri]
  (->GitlabService (build-gitlab-service api-key api-secret callback-uri)))
