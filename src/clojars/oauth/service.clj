(ns clojars.oauth.service
  (:import
   (com.github.scribejava.core.builder
    ServiceBuilder)))

(defprotocol OauthService
  (authorization-url [service])
  (access-token [service code])
  (provider-name [service]))

(defrecord MockOauthService [provider config]
  OauthService

  (authorization-url [this]
    (:authorize-uri config))

  (access-token [this code]
    (:access-token config))

  (provider-name [_]
    provider))

(defn new-mock-oauth-service [provider config]
  (->MockOauthService provider config))

(defn build-oauth-service
  [api-key api-secret callback-uri api-instance]
  (-> (ServiceBuilder. api-key)
      (.apiSecret api-secret)
      (.callback callback-uri)
      ;; GitLab's CloudFlare setup will reject requests w/o a
      ;; user-agent, so we just set it for all providers
      (.userAgent "Clojars.org OAuth")
      (.build api-instance)))

(defn- get-user-details-dispatch
  [service _http-client _token]
  (provider-name service))

(defmulti get-user-details
  #'get-user-details-dispatch)
