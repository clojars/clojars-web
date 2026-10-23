(ns clojars.models.user
  (:require
   [clojars.db :as db]
   [clojars.models.deploy-token :as deploy-token-m]
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
                :created (db/get-time)}
        result (sql/insert! db :users (set/rename-keys record {:username db/user-column}))]
    (doseq [groupname (default-user-groups username)]
      (permission-m/add-member* db groupname permission-m/SCOPE-ALL username "clojars" true)
      (group-verification-m/verify-group! db username groupname))
    (assoc record :id (:users/id result))))

(defn update-user
  [db account email password]
  (sql/update! db :users
               (cond-> {:email email}
                 (seq password) (assoc :password (db/bcrypt password)))
               {db/user-column account})
  {:email    (str/lower-case email)
   :username account
   :account  account})

(defn- rename-user-tree
  [tx from-name to-name]
  (sql/update! tx :permissions
               {db/user-column to-name}
               {db/user-column from-name})
  (sql/update! tx :permissions
               {:added_by to-name}
               {:added_by from-name})
  (sql/update! tx :group_verifications
               {:verified_by to-name}
               {:verified_by from-name})
  (sql/update! tx :jars
               {db/user-column to-name}
               {db/user-column from-name})
  (sql/update! tx :audit
               {db/user-column to-name}
               {db/user-column from-name}))

(defn rename-user
  [db old-name new-name]
  (jdbc/with-transaction [tx db]
    (sql/update! tx :users
                 {db/user-column new-name}
                 {db/user-column old-name})
    (rename-user-tree tx old-name new-name)
    {:username new-name
     :account  new-name}))

(defn- append-merged-usernames
  [tx main-username other-usernames]
  (db/execute!
   tx {:update :users
       :set {:merged_usernames [:array_cat :merged_usernames [:array other-usernames]]}
       :where [:= :user main-username]}))

(defn- delete-users!
  [tx usernames]
  (db/execute! tx {:delete-from :users
                   :where [:in :user usernames]}))

(defn find-user
  [db username]
  (first
   (db/q db
         {:select :*
          :from :users
          :where [:= :user username]
          :limit 1})))

(defn- assert-find-user
  [db username]
  (if-some [user (find-user db username)]
    user
    (throw (ex-info "User does not exist"
                    {:username username}))))

(defn- remove-future-duplicate-membership
  "Finds all memberships for all users involved in the merge. It then inactivates
  all but one of those memberships for each group and scope, preventing
  duplicated group membership after the merge. \"*\" scoped memberships trump
  all other scopes for that group. It doesn't matter what user the remaining
  memberships are for, as they will all be assigned to the main-username by the
  time the merge is done. The preserved memberships are the ones that are
  admin (if available) and the broadest scope."
  [tx main-username other-usernames]
  (let [all-memberships (permission-m/find-memberships-for-users
                         tx (conj other-usernames main-username))]
    (doseq [group-memberships (vals (group-by :group_name all-memberships))
            :when (> (count group-memberships) 1)]
      (->> group-memberships
           ;; false sorts before true, so we flip admin. "*" sorts before alpha strings.
           (sort-by (fn [{:keys [admin scope]}] [(not admin) scope]))
           (reduce
            (fn [[seen-scopes to-inactivate] {:as membership :keys [scope]}]
              (if (or (contains? seen-scopes permission-m/SCOPE-ALL)
                      (contains? seen-scopes scope))
                [seen-scopes (conj to-inactivate membership)]
                [(conj seen-scopes scope) to-inactivate]))
            [#{} #{}])
           (peek)
           (run! (fn [{group :group_name, scope :scope, username :user}]
                   (permission-m/inactivate-member tx group scope username "clojars")))))))

(defn merge-users!
  "Merges users for `other-usernames` into the user for `main-username`, and deletes the `other-usernames` users.
  
  The main user inherits all the groups/jars of any of the merged users.

  The `other-usernames` are stored on the main user in the merged_usernames
  array so can still be used for logging in, and the register logic can still
  consider the usernames as unavailable."
  [db main-username other-usernames]
  (let [{:as _main-user main-user-id :id} (assert-find-user db main-username)
        other-users-map (into {} (comp
                                  (map #(assert-find-user db %))
                                  (map (juxt :user identity)))
                              other-usernames)]
    (jdbc/with-transaction [tx db]
      (remove-future-duplicate-membership tx main-username other-usernames)
      (doseq [merging-username other-usernames
              :let [tokens (deploy-token-m/find-deploy-tokens-for-user
                            db
                            (:id (other-users-map merging-username)))]]
        (rename-user-tree tx merging-username main-username)
        (doseq [{token-id :id} tokens]
          (deploy-token-m/reassign-token tx token-id main-user-id)))
      (append-merged-usernames tx main-username
                               (into other-usernames
                                     ;; Include any users that were already merged
                                     ;; into the other users, as we want to preserve
                                     ;; that history
                                     (mapcat :merged_usernames)
                                     (vals other-users-map)))
      (delete-users! tx other-usernames))))

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

(defn all-users
  [db]
  (db/q db
        {:select [:user]
         :from :users
         :order-by [[:user :asc]]}))

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
