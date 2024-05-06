(ns clojars.oauth.service
  (:import
   (com.github.scribejava.core.builder
    ServiceBuilder)
   (com.github.scribejava.core.builder.api
    DefaultApi20)))

(set! *warn-on-reflection* true)

(defprotocol OauthService
  (authorization-url [service])
  (access-token [service code])
  (provider-name [service]))

(defrecord MockOauthService [provider config]
  OauthService

  (authorization-url [_this]
    (:authorize-uri config))

  (access-token [_this _code]
    (:access-token config))

  (provider-name [_]
    provider))

(defn new-mock-oauth-service [provider config]
  (->MockOauthService provider config))

(defn build-oauth-service
  [api-key ^String api-secret ^String callback-uri ^DefaultApi20 api-instance]
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
