(ns clojars.oauth.service)

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

(defn- get-user-details-dispatch
  [service _http-client _token]
  (provider-name service))

(defmulti get-user-details
  #'get-user-details-dispatch)
