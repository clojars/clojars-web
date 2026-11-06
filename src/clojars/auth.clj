(ns clojars.auth
  (:require
   [buddy.core.codecs :as codecs]
   [cemerick.friend :as friend]
   [cemerick.friend.credentials :as creds]
   [cemerick.friend.workflows :as workflows]
   [clojars.db :as db]
   [clojars.event :as event]
   [clojars.hibp :as hibp]
   [clojars.log :as log]
   [clojars.models.deploy-token :as deploy-token-m]
   [clojars.models.permission :as permission-m]
   [clojars.models.session :as session-m]
   [clojars.models.user :as user-m]
   [clojars.routes.common :as routes-common]
   [clojars.time :as time]
   [clojars.util :as util]
   [clojure.string :as str]
   [one-time.core :as ot]
   [ring.util.codec :as codec]
   [ring.util.request :as req]
   [ring.util.response :as response]))

(set! *warn-on-reflection* true)

(defn try-account [f]
  (f (:username (friend/current-authentication))))

(defn with-account [f]
  (friend/authenticated (try-account f)))

(defn authorized-admin? [db account group scope]
  (when account
    (let [adminnames (permission-m/group-adminnames db group scope)
          allnames (permission-m/group-allnames db group)]
      (or (some #{account} adminnames) (empty? allnames)))))

(defn authorized-group-access? [db account group]
  (when account
    (some #{account} (permission-m/group-allnames db group))))

(defn require-admin-authorization [db account group scope f]
  (if (authorized-admin? db account group scope)
    (f)
    (friend/throw-unauthorized friend/*identity*
                               {:cemerick.friend/required-roles group})))

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
      (user-m/disable-otp! db username)
      (event/emit event-emitter :mfa-deactivated
                  {:username username
                   :source :recovery-code})
      true)
    (valid-totp-token? otp user)))

(defn- get-param
  [key form-params params]
  (or (get form-params (name key)) (get params key "")))

(defn- make-auth
  [event-emitter {:as user username :user}]
  (event/emit event-emitter :user-logged-in {:user user})
  (log/info {:status :success})
  (workflows/make-auth {:username username}
                       {::friend/workflow          :interactive-form
                        ::friend/redirect-on-auth? true}))

(defn- maybe-complete-login
  [event-emitter user]
  (if (user-m/require-email-verification-to-login? user)
    (do
      (log/info {:status :pending-email-verification})
      (event/emit event-emitter :email-verification-email-requested {:user user})
      (response/redirect "/login/verify-email"))
    (make-auth event-emitter user)))

(def pwned-password-login-message
  (str "Your password has appeared in a data breach, so we have disabled it. "
       "Please reset your password to log in."))

(def password-disabled-message
  "Your password has been disabled. Please reset your password to log in.")

(defn- redirect-to-forgot-password
  "Redirects to the forgot password page with `username` prefilled."
  [username message]
  (assoc (response/redirect
          (str "/forgot-password?" (codec/form-encode {:email-or-username username})))
         :flash message))

(defn- reject-pwned-password
  "Clears the password of a user who logged in with a known-compromised
  password, logs out all of their sessions, and sends them to reset it."
  [db event-emitter request username]
  (log/info {:status :failed
             :reason :password-pwned})
  (user-m/clear-password! db username)
  (session-m/delete-sessions-for-user! db username)
  (event/emit event-emitter :password-pwned
              (merge {:username username}
                     (routes-common/request-details request)))
  ;; Drop this browser's session too, or the flash middleware writes it back.
  (-> (redirect-to-forgot-password username pwned-password-login-message)
      (assoc :session nil)))

(defn- verify-password-step
  [db event-emitter hibp {:as request :keys [form-params params session]}]
  (let [username (get-param :username form-params params)
        password (get-param :password form-params params)
        user     (user-m/find-user db username)]
    (log/with-context {:tag      :authentication
                       :username username
                       :type     :password}
      (cond
        (and user (not (user-m/has-password? user)))
        (do
          (log/info {:status :failed
                     :reason :password-disabled})
          (redirect-to-forgot-password username password-disabled-message))

        (not (user-m/password-matches? user password))
        (do
          (log/info {:status :failed
                     :reason :password-incorrect})
          (response/redirect (str "/login?" (codec/form-encode {:login_failed "Y" :username username}))))

        (hibp/pwned? hibp password)
        (reject-pwned-password db event-emitter request (:user user))

        (:otp_active user)
        (do
          (log/info {:status :pending-mfa})
          (-> (response/redirect "/login/mfa")
              (assoc :session (assoc session ::pending-mfa-username username))))

        :else
        (maybe-complete-login event-emitter user)))))

(defn- verify-mfa-step
  [db event-emitter {:as _request :keys [form-params params session]}]
  (if-some [username (::pending-mfa-username session)]
    (let [otp  (get-param :otp form-params params)
          user (user-m/find-user db username)]
      (log/with-context {:tag      :authentication
                         :username username
                         :type     :mfa}
        (if (validate-otp db event-emitter user otp)
          (maybe-complete-login event-emitter user)
          (do
            (log/info {:status :failed
                       :reason :otp-incorrect})
            (response/redirect "/login/mfa?otp_failed=Y")))))
    ;; No pending username — someone hit /login/mfa directly
    (response/redirect "/login")))

(defn pending-mfa?
  [session]
  (some? (::pending-mfa-username session)))

(defn pending-email-verification?
  "Returns true if the session is in the email-verification pending state."
  [session]
  (some? (::pending-email-verification-username session)))

(defn interactive-form-with-mfa-workflow
  [db event-emitter hibp]
  (fn [{:as request :keys [request-method]}]
    (when (= :post request-method)
      (let [path (req/path-info request)]
        (case path
          "/login"     (verify-password-step db event-emitter hibp request)
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
      (deploy-token-m/is-deploy-token? password))
    true))

(defn token-expired?
  [{:as _token :keys [expires_at]}]
  (and expires_at
       (time/date-after (db/get-time) expires_at)))

(defn token-credential-fn [db]
  (fn [{username-or-email :username password :password}]
    (log/with-context {:tag :authentication
                       :username username-or-email
                       :type :token}
      (let [password-hash (when password
                            (deploy-token-m/hash-deploy-token password))
            username (:user (user-m/find-user-by-user-or-email db username-or-email))]
        (if-let [token (and username
                            password
                            (->> (deploy-token-m/find-user-tokens-by-username db username)
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
              (deploy-token-m/set-deploy-token-used db (:id token))
              (when-not (:token_hash token)
                ;; set token hashes for tokens that were created before we
                ;; added the hash to the db
                (deploy-token-m/set-deploy-token-hash db (:id token) password))
              (log/info {:status :success})
              {:username username
               :token token}))
          (do
            (log/audit db {:tag :invalid-token
                           :message "The given token either doesn't exist, isn't yours, or is disabled"})
            (log/info {:status :failed
                       :reason :invalid-token})))))))
