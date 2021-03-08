(ns clojars.oauth.service)

(defprotocol OauthService
  (authorization-url [service])
  (access-token [service code])
  (get-user-details [service token])
  (provider-name [service]))

(defrecord MockOauthService [provider config]
  OauthService

  (authorization-url [this]
    (:authorize-uri config))

  (access-token [this code]
    (:access-token config))

  (get-user-details [this token]
    (select-keys config [:emails :login]))

  (provider-name [_]
    provider))

(defn new-mock-oauth-service [provider config]
  (->MockOauthService provider config))
