(ns clojars.models.user
  (:require
   [cemerick.friend.credentials :as creds]
   [clojars.db :as db]
   [clojars.models.group-verification :as group-verification-m]
   [clojars.models.permission :as permission-m]
   [clojars.time :as time]
   [clojure.set :as set]
   [clojure.string :as str]
   [next.jdbc :as jdbc]
   [next.jdbc.sql :as sql]
   [one-time.core :as ot])
  (:import
   (java.sql
    Timestamp)
   (java.time
    Instant)
   (java.util
    Date)))

(set! *warn-on-reflection* true)

(defn default-user-groups
  [username]
  (mapv #(format "%s.clojars.%s" % username)
        ["net" "org"]))

(defn add-user
  [db email username password]
  (let [record {:email (str/lower-case email)
                :username username
                :password (db/bcrypt password)
                :send_deploy_emails true
                :created (db/get-time)}]
    (sql/insert! db :users (set/rename-keys record {:username db/user-column}))
    (doseq [groupname (default-user-groups username)]
      (permission-m/add-member* db groupname permission-m/SCOPE-ALL username "clojars" true)
      (group-verification-m/verify-group! db username groupname))
    record))

(defn update-user
  [db account email password]
  (sql/update! db :users
               (cond-> {:email email}
                 (seq password) (assoc :password (db/bcrypt password)))
               {db/user-column account})
  {:email    (str/lower-case email)
   :username account
   :account  account})

(defn rename-user
  [db old-name new-name]
  (jdbc/with-transaction [tx db]
    (sql/update! tx :users
                 {db/user-column new-name}
                 {db/user-column old-name})
    (sql/update! tx :permissions
                 {db/user-column new-name}
                 {db/user-column old-name})
    (sql/update! tx :permissions
                 {:added_by new-name}
                 {:added_by old-name})
    (sql/update! tx :group_verifications
                 {:verified_by new-name}
                 {:verified_by old-name})
    (sql/update! tx :jars
                 {db/user-column new-name}
                 {db/user-column old-name})
    (sql/update! tx :audit
                 {db/user-column new-name}
                 {db/user-column old-name})
    {:username new-name
     :account  new-name}))

(defn update-user-notifications
  [db account prefs]
  (sql/update! db :users prefs {db/user-column account}))

(defn reset-user-password
  [db username reset-code password]
  (assert (not (str/blank? reset-code)))
  (assert (some? username))
  (sql/update! db :users
               {:password                       (db/bcrypt password)
                :password_reset_code            nil
                :password_reset_code_created_at nil}
               {:password_reset_code reset-code
                db/user-column username}))

(defn has-password?
  "Returns true if the user has a password set. A user's password is blank
  after it was cleared, e.g. for appearing in a breach."
  [user]
  (not (str/blank? (:password user))))

(defn password-matches?
  "Returns true if `password` matches the user's password. Always false when
  either is blank, since bcrypt throws on a blank hash."
  [user password]
  (boolean
   (and (has-password? user)
        (not (str/blank? password))
        (creds/bcrypt-verify password (:password user)))))

(defn clear-password!
  "Clears the user's password so they can't log in with it. They will need to
  use the forgot password flow to set a new one."
  [db username]
  (sql/update! db :users {:password ""} {db/user-column username}))

(defn all-users
  [db]
  (db/q db
        {:select [:user]
         :from :users
         :order-by [[:user :asc]]}))

(defn find-user
  [db username]
  (first
   (db/q db
         {:select :*
          :from :users
          :where [:= :user username]
          :limit 1})))

(defn find-user-by-user-or-email
  [db username-or-email]
  (first
   (db/q db
         {:select :*
          :from :users
          :where [:or
                  [:= :user username-or-email]
                  [:= :email (str/lower-case username-or-email)]]
          :limit 1})))

(defn find-user-by-email-in
  [db emails]
  (first
   (db/q db
         {:select :*
          :from :users
          :where [:in :email (mapv str/lower-case emails)]
          :limit 1})))

(defn find-user-by-password-reset-code
  [db reset-code]
  (first
   (db/q db
         {:select :*
          :from :users
          :where [:and
                  [:= :password_reset_code reset-code]
                  [:>= :password_reset_code_created_at
                   (Timestamp/from (time/days-ago 1))]]
          :limit 1})))

(defn find-user-by-id
  [db id]
  (first
   (db/q db
         {:select :*
          :from :users
          :where [:= :id id]
          :limit 1})))

(defn set-password-reset-code!
  [db username]
  (let [reset-code (db/generate-secure-hex-token 20)]
    (sql/update! db :users
                 {:password_reset_code            reset-code
                  :password_reset_code_created_at (db/get-time)}
                 {db/user-column username})
    reset-code))

(defn clear-password-reset-code!
  [db username]
  (sql/update! db :users
               {:password_reset_code nil}
               {db/user-column username}))

(defn set-otp-secret-key!
  [db username]
  (let [secret-key (ot/generate-secret-key)]
    (sql/update! db :users
                 {:otp_secret_key secret-key}
                 {db/user-column username})))

(defn enable-otp!
  [db username]
  (let [recovery-code (str (random-uuid))]
    (sql/update! db :users
                 {:otp_active        true
                  :otp_recovery_code (db/bcrypt recovery-code)}
                 {db/user-column username})
    recovery-code))

(defn disable-otp!
  [db username]
  (sql/update! db :users
               {:otp_active        false
                :otp_recovery_code nil
                :otp_secret_key    nil}
               {db/user-column username}))

(defn user-has-mfa?
  [db username]
  (some? (:otp_recovery_code (find-user db username))))

;; Email verification

(def email-verification-required-date #inst "2026-10-12")

(defn require-email-verification-to-login?
  "Returns true for users created after the verification boundary date. Will be
  removed when we require validation for all users."
  [{:as _user created :created email-verified? :email_verified}]
  (and (not email-verified?)
       (time/date-after created email-verification-required-date)))

(def verify-email-resend-rate-limit-seconds 60)

(defn verify-email-send-allowed?
  [{:as _user
    ^Date code-created-at :email_verification_code_created_at
    email-verified? :email_verified}]
  (and (not email-verified?)
       (let [now (time/now)
             last-resend (if code-created-at
                           (.toInstant code-created-at)
                           Instant/EPOCH)
             elapsed-s (time/seconds-between last-resend now)]
         (> elapsed-s verify-email-resend-rate-limit-seconds))))

(defn set-email-verification-code
  [db username]
  (let [code (db/generate-secure-hex-token 20)]
    (sql/update! db :users
                 {:email_verification_code            code
                  :email_verification_code_created_at (db/get-time)}
                 {db/user-column username})
    code))

(defn find-user-by-email-verification-code
  [db code]
  (first
   (db/q db
         {:select :*
          :from :users
          :where [:and
                  [:= :email_verification_code code]
                  [:>= :email_verification_code_created_at
                   (Timestamp/from (time/days-ago 1))]]
          :limit 1})))

(defn mark-email-verified
  [db username]
  (sql/update! db :users
               {:email_verified                     true
                :email_verified_at                  (db/get-time)
                :email_verification_code            nil
                :email_verification_code_created_at nil}
               {db/user-column username}))

(defn mark-email-unverified
  [db username]
  (sql/update! db :users
               {:email_verified                     false
                :email_verified_at                  nil
                :email_verification_code            nil
                :email_verification_code_created_at nil}
               {db/user-column username}))
