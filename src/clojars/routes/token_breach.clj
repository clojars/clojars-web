(ns clojars.routes.token-breach
  (:require
   [buddy.core.codecs :as codecs]
   [buddy.core.dsa :as dsa]
   [buddy.core.keys :as keys]
   [cheshire.core :as json]
   [clj-http.client :as client]
   [clojars.db :as db]
   [clojars.event :as event]
   [compojure.core :as compojure :refer [POST]]
   [ring.util.response :as response]))

(defn- get-github-key
  "Retrieves the public key text from the github api for the key
  identifier, then converts the key text to a key object."
  [identifier]
  (when identifier
    (some->> (client/get "https://api.github.com/meta/public_keys/token_scanning"
                         {:as :json})
             :body
             :public_keys
             (some (fn [{:keys [key_identifier key]}]
                     (when (= identifier key_identifier)
                       key)))
             (keys/str->public-key))))

(defn- valid-github-request?
  "Verifies the request was signed using GitHub's key.
  https://developer.github.com/partnerships/secret-scanning/"
  [headers body-str]
  (let [key-id (get headers "github-public-key-identifier")
        key-sig (get headers "github-public-key-signature")
        key (get-github-key key-id)]
    (when (and body-str key key-sig)
      (dsa/verify body-str
                  (codecs/b64->bytes key-sig)
                  {:key key :alg :ecdsa+sha256}))))

;; - add timing logs

(defn- token-response
  [{:keys [token type]} found?]
  {:token_raw  token
   :token_type type
   :label      (if found? "true_positive" "false_positive")})

(defn- check-token
  [db event-emitter {:as token-data :keys [token url]}]
  (if-some [{:as db-token :keys [id disabled user_id]}
            (db/find-token-by-value db token)]
    (do
      (when (not disabled)
        (db/disable-deploy-token db id))
      (event/emit event-emitter :token-breached
                  {:user-id         user_id
                   :token-disabled? disabled
                   :token-name      (:name db-token)
                   :commit-url      url})
      (token-response token-data true))
    (token-response token-data false)))

(defn- handle-github-token-breach
  [db event-emitter {:as _request :keys [headers body]}]
  (let [body-str (slurp body)]
    (if (valid-github-request? headers body-str)
      (let [data (json/parse-string body-str true)]
        (response/response (mapv (partial check-token db event-emitter) data)))
      (response/status 422))))

(defn routes [db event-emitter]
  (compojure/routes
   (POST "/token-breach/github" request
         (handle-github-token-breach db event-emitter request))))
