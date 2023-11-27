(ns clojars.auth
  (:require
   [cemerick.friend :as friend]
   [cemerick.friend.credentials :as creds]
   [cemerick.friend.util :as friend.util]
   [cemerick.friend.workflows :as workflows]
   [clojars.db :as db]
   [clojars.event :as event]
   [clojars.log :as log]
   [clojars.util :as util]
   [clojure.string :as str]
   [one-time.core :as ot]
   [ring.util.request :as req])
  (:import
   org.apache.commons.codec.binary.Base64))

(defn try-account [f]
  (f (:username (friend/current-authentication))))

(defn with-account [f]
  (friend/authenticated (try-account f)))

(defn authorized-admin? [db account group]
  (when account
    (let [adminnames (db/group-adminnames db group)
          allnames (db/group-allnames db group)]
      (or (some #{account} adminnames) (empty? allnames)))))

(defn authorized-member? [db account group]
  (when account
    (some #{account} (db/group-membernames db group))))

(defn authorized-group-access? [db account group]
  (when account
    (some #{account} (db/group-allnames db group))))

(defn require-admin-authorization [db account group f]
  (if (authorized-admin? db account group)
    (f)
    (friend/throw-unauthorized friend/*identity*
                               {:cemerick.friend/required-roles group})))

(defn- get-param
  [key form-params params]
  (or (get form-params (name key)) (get params key "")))

;; copied from cemerick.friend/workflows and modified to support MFA
(defn interactive-form-with-mfa-workflow
  [& {:keys [redirect-on-auth?] :as form-config
      :or {redirect-on-auth? true}}]
  (fn [{:keys [request-method params form-params] :as request}]
    (when (and (= (friend.util/gets :login-uri
                                    form-config
                                    (::friend/auth-config request))
                  (req/path-info request))
               (= :post request-method))
      (let [creds {:username (get-param :username form-params params)
                   :password (get-param :password form-params params)
                   :otp (get-param :otp form-params params)}
            {:keys [username password]} creds]
        (if-let [user-record (and username password
                                  ((friend.util/gets :credential-fn
                                                     form-config
                                                     (::friend/auth-config request))
                                   (with-meta creds {::friend/workflow :interactive-form})))]
          (workflows/make-auth user-record
                               {::friend/workflow :interactive-form
                                ::friend/redirect-on-auth? redirect-on-auth?})
          ((or (friend.util/gets :login-failure-handler
                                 form-config
                                 (::friend/auth-config request))
               #'workflows/interactive-login-redirect)
           (update request ::friend/auth-config merge form-config)))))))

(defn parse-authorization-header
  "Parses a Basic auth header into username and password."
  [authorization]
  (when (and authorization
             (re-matches #"\s*Basic\s+(.+)" authorization))
    (when-let [[_ username password]
               (try
                 (-> (re-matches #"\s*Basic\s+(.+)" authorization)
                     ^String second
                     (.getBytes "UTF-8")
                     Base64/decodeBase64
                     (String. "UTF-8")
                     (#(re-find #"([^:]*):(.*)" %)))
                 (catch Exception e
                   (log/error {:tag :failed-parsing-authorization-header
                               :error e})))]
      {:username username
       :password password})))

(defn unauthed-or-token-request?
  "Returns true if:
  * there is no authorization header
  - or -
  * there is a authorization header
  * the password in the header is shaped like a deploy token"
  [{:as _request :keys [headers]}]
  (if-let [authorization (get headers "authorization")]
    (let [{:keys [password]} (parse-authorization-header authorization)]
      (db/is-deploy-token? password))
    true))

(defn token-expired?
  [{:as _token :keys [expires_at]}]
  (and expires_at
       (.after (db/get-time) expires_at)))

(defn token-credential-fn [db]
  (fn [{username-or-email :username password :password}]
    (log/with-context {:tag :authentication
                       :username username-or-email
                       :type :token}
      (let [password-hash (when password
                            (db/hash-deploy-token password))
            username (:user (db/find-user-by-user-or-email db username-or-email))]
        (if-let [token (and username
                            password
                            (->> (db/find-user-tokens-by-username db username)
                                 (remove :disabled)
                                 (filter #(let [{:keys [token_hash]} %]
                                            (or (not token_hash) ;; pre-hash token
                                                (= password-hash token_hash))))
                                 (some #(when (creds/bcrypt-verify password (:token %)) %))))]
          (if (token-expired? token)
            (do
              (log/audit db {:tag :expired-token
                             :message "The given token is expired"})
              (log/info {:status :failed
                         :reason :expired-token}))
            (do
              (db/set-deploy-token-used db (:id token))
              (when-not (:token_hash token)
                ;; set token hashes for tokens that were created before we
                ;; added the hash to the db
                (db/set-deploy-token-hash db (:id token) password))
              (log/info {:status :success})
              {:username username
               :token token}))
          (do
            (log/audit db {:tag :invalid-token
                           :message "The given token either doesn't exist, isn't yours, or is disabled"})
            (log/info {:status :failed
                       :reason :invalid-token})))))))

(defn valid-totp-token?
  [otp {:as _user :keys [otp_secret_key]}]
  (when-let [otp-n (-> otp
                       (str/replace #"\s+" "")
                       (util/parse-long))]
    (ot/is-valid-totp-token? otp-n otp_secret_key)))

(defn- validate-otp
  [db
   event-emitter
   {:as user
    recovery-code :otp_recovery_code
    username :user}
   otp]
  (if (creds/bcrypt-verify otp recovery-code)
    (do
      (db/disable-otp! db username)
      (event/emit event-emitter :mfa-deactivated
                  {:username username
                   :source :recovery-code})
      true)
    (valid-totp-token? otp user)))

(defn password-credential-fn [db event-emitter]
  (fn [{:keys [username password otp]}]
    (log/with-context {:tag :authentication
                       :username username
                       :type :password
                       :otp? (boolean otp)}
      (if (not (str/blank? password))
        (let [{:as user :keys [otp_active]} (db/find-user db username)]
          (if (and (not (str/blank? (:password user)))
                   (creds/bcrypt-verify password (:password user))
                   (or (not otp_active)
                       (validate-otp db event-emitter user otp)))
            (do
              (log/info {:status :success})
              {:username username})
            (log/info {:status :failed
                       :reason :password-or-otp-incorrect})))
        (log/info {:status :failed
                   :reason :password-blank})))))
