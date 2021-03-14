(ns clojars.oauth.gitlab
  (:require
   [clojars.oauth.service :as oauth-service]
   [clojars.remote-service :as remote-service :refer [defendpoint]])
  (:import
   (com.github.scribejava.core.builder.api
    DefaultApi20)))

(defrecord GitlabService [service]
  oauth-service/OauthService

  (authorization-url [_]
    (.getAuthorizationUrl service {"scope" "read_user"}))

  (access-token [_ code]
    (.getAccessToken (.getAccessToken service code)))

  (provider-name [_]
    "GitLab"))

(defendpoint get-user
  [_client token]
  {:method :get
   :url "https://gitlab.com/api/v4/user"
   :oauth-token token})

;; NOTE: this only uses the primary email from GitLab since there
;; doesn't appear to be a way to tell if a secondary email has been
;; verified. https://docs.gitlab.com/ee/api/users.html#list-emails-for-user
;; returns all secondary emails, verified or not, but doesn't include
;; the verification status. - Toby 2021-03-13
(defmethod oauth-service/get-user-details "GitLab"
  [_ http-client token]
  (let [{:keys [email username]} (get-user http-client token)]
    {:emails [email]
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
