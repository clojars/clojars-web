(ns clojars.routes.token-breach
  (:require
   [buddy.core.codecs.base64 :as base64]
   [buddy.core.dsa :as dsa]
   [buddy.core.keys :as keys]
   [cheshire.core :as json]
   [clj-http.client :as client]
   [clojars.db :as db]
   [compojure.core :as compojure :refer [POST]]
   [ring.util.response :as response]))

(defn- get-github-key
  "Retrieves the public key text from the github api for the key
  identifier, then converts the key text to a key object."
  [identifier]
  (->> (client/get "https://api.github.com/meta/public_keys/token_scanning"
                   {:as :json})
       :body
       :public_keys
       (some (fn [{:keys [key_identifier key]}]
               (when (= identifier key_identifier)
                 key)))
       (keys/str->public-key)))

(defn- valid-github-request?
  "Verifies the request was signed using GitHub's key.
  https://developer.github.com/partnerships/secret-scanning/"
  [headers body-str]
  (let [key-id (get headers "github-public-key-identifier")
        key-sig (get headers "github-public-key-signature")
        key (get-github-key key-id)
        sig (base64/decode key-sig)]
    (dsa/verify body-str sig {:key key :alg :ecdsa+sha256})))

(defn- send-email
  [mailer {:as _user :keys [email]} {:as _token :keys [disabled name]} url]
  (mailer
   email
   "Deploy token found on GitHub"
   (->> ["Hello,"
         (format
          "We received a notice from GitHub that your deploy token named '%s' was found by their secret scanning service."
          name)
         (format "The commit was found at: %s" url)
         (if disabled
           "The token was already disabled, so we took no further action."
           "This token has been disabled to prevent malicious use.")]
        (interpose "\n\n")
        (apply str))))

(defn- handle-github-token-breach
  [db mailer {:as _request :keys [headers body]}]
  (let [body-str (slurp body)]
    (if (valid-github-request? headers body-str)
      (let [data (json/parse-string body-str true)]
        (doseq [{:keys [token url]} data]
          (when-let [{:as db-token :keys [id disabled user_id]}
                     (db/find-token-by-value db token)]
            (when (not disabled)
              (db/disable-deploy-token db id))
            (send-email mailer (db/find-user-by-id db user_id) db-token url)))
        (response/status 200))
      (response/status 422))))

(defn routes [db mailer]
  (compojure/routes
   (POST "/token-breach/github" request
         (handle-github-token-breach db mailer request))))
