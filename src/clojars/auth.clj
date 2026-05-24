(ns clojars.auth
  (:require
   [buddy.core.codecs :as codecs]
   [cemerick.friend :as friend]
   [cemerick.friend.credentials :as creds]
   [cemerick.friend.workflows :as workflows]
   [clojars.db :as db]
   [clojars.event :as event]
   [clojars.log :as log]
   [clojars.util :as util]
   [clojure.string :as str]
   [one-time.core :as ot]
   [ring.util.request :as req]
   [ring.util.response :as response])
  (:import
   java.sql.Timestamp))

(set! *warn-on-reflection* true)

(defn try-account [f]
  (f (:username (friend/current-authentication))))

(defn with-account [f]
  (friend/authenticated (try-account f)))

(defn authorized-admin? [db account group scope]
  (when account
    (let [adminnames (db/group-adminnames db group scope)
          allnames (db/group-allnames db group)]
      (or (some #{account} adminnames) (empty? allnames)))))

(defn authorized-group-access? [db account group]
  (when account
    (some #{account} (db/group-allnames db group))))

(defn require-admin-authorization [db account group scope f]
  (if (authorized-admin? db account group scope)
    (f)
    (friend/throw-unauthorized friend/*identity*
                               {:cemerick.friend/required-roles group})))

(defn- verify-password
  [db username password]
  (when-not (str/blank? password)
    (let [user (db/find-user db username)]
      (when (and (not (str/blank? (:password user)))
                 (creds/bcrypt-verify password (:password user)))
        user))))

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

(defn- get-param
  [key form-params params]
  (or (get form-params (name key)) (get params key "")))

(defn- make-auth
  [username]
  (log/info {:status :success})
  (workflows/make-auth {:username username}
                       {::friend/workflow          :interactive-form
                        ::friend/redirect-on-auth? true}))

(defn- verify-password-step
  [db {:as _request :keys [form-params params session]}]
  (let [username (get-param :username form-params params)
        password (get-param :password form-params params)]
    (log/with-context {:tag      :authentication
                       :username username
                       :type     :password}
      (if-some [{:as _user :keys [otp_active]} (verify-password db username password)]
        (if otp_active
          (do
            (log/info {:status :pending-mfa})
            (-> (response/redirect "/login/mfa")
                (assoc :session (assoc session ::pending-mfa-username username))))
          (make-auth username))
        (do
          (log/info {:status :failed
                     :reason :password-incorrect})
          (response/redirect (format "/login?login_failed=Y&username=%s" username)))))))

(defn- verify-mfa-step
  [db event-emitter {:as _request :keys [form-params params session]}]
  (if-some [username (::pending-mfa-username session)]
    (let [otp  (get-param :otp form-params params)
          user (db/find-user db username)]
      (log/with-context {:tag      :authentication
                         :username username
                         :type     :mfa}
        (if (validate-otp db event-emitter user otp)
          (make-auth username)
          (do
            (log/info {:status :failed
                       :reason :otp-incorrect})
            (response/redirect "/login/mfa?otp_failed=Y")))))
    ;; No pending username — someone hit /login/mfa directly
    (response/redirect "/login")))

(defn pending-mfa?
  [session]
  (some? (::pending-mfa-username session)))

(defn interactive-form-with-mfa-workflow
  [db event-emitter]
  (fn [{:as request :keys [request-method]}]
    (when (= :post request-method)
      (let [path (req/path-info request)]
        (case path
          "/login"     (verify-password-step db request)
          "/login/mfa" (verify-mfa-step db event-emitter request)
          ;; else
          nil)))))

(defn parse-authorization-header
  "Parses a Basic auth header into username and password."
  [authorization]
  (when (and authorization
             (re-matches #"\s*Basic\s+(.+)" authorization))
    (when-let [[_ username password]
               (try
                 (let [[_ creds] (re-matches #"\s*Basic\s+(.+)" authorization)
                       decoded-creds (codecs/b64->str creds)]
                   (re-find #"([^:]*):(.*)" decoded-creds))
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
       (.after (db/get-time) ^Timestamp expires_at)))

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
