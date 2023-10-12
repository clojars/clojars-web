(ns clojars.db
  (:require
   [buddy.core.codecs :as buddy.codecs]
   [buddy.core.hash :as buddy.hash]
   [cemerick.friend.credentials :as creds]
   [clj-time.coerce :as time.coerce]
   [clj-time.core :as time]
   [clojars.config :refer [config]]
   [clojars.maven :as mvn]
   [clojars.util :refer [filter-some]]
   [clojure.edn :as edn]
   [clojure.java.jdbc :as jdbc]
   [clojure.set :as set]
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [honey.sql :as hsql]
   [one-time.core :as ot])
  (:import
   (clojure.lang
    Keyword)
   (java.security
    SecureRandom)
   (java.sql
    Timestamp)
   (java.util
    UUID)
   (org.postgresql.util
    PGobject)))

(def reserved-names
  #{"about"
    "admin"
    "administrator"
    "api"
    "blog"
    "browse"
    "clojar"
    "clojars"
    "clojure"
    "contact"
    "create"
    "css"
    "dashboard"
    "devel"
    "development"
    "doc"
    "docs"
    "download"
    "email"
    "files"
    "group"
    "groups"
    "help"
    "images"
    "index"
    "jar"
    "jars"
    "js"
    "login"
    "logout"
    "maven"
    "mfa"
    "new"
    "notification-preferences"
    "options"
    "pages"
    "password"
    "prod"
    "production"
    "profile"
    "register"
    "releases"
    "repo"
    "repos"
    "root"
    "search"
    "settings"
    "snapshots"
    "stats"
    "status"
    "terms"
    "test"
    "testing"
    "token-breach"
    "tokens"
    "upload"
    "user"
    "username"
    "verify"
    "webmaster"
    "welcome"})

;; ----- JDBC extensions -----

;; read/write postgres enums
;; from https://www.bevuta.com/en/blog/using-postgresql-enums-in-clojure/

(defn- kw->pgenum
  [kw]
  (let [type (-> (namespace kw)
                 (str/replace "-" "_"))
        value (name kw)]
    (doto (PGobject.)
      (.setType type)
      (.setValue value))))

(extend-type Keyword
  jdbc/ISQLValue
  (sql-value [kw]
    (kw->pgenum kw)))

(let [schema-enums #{"single_use_status"}]
  (extend-type String
    jdbc/IResultSetReadColumn
    (result-set-read-column
      [val rsmeta idx]
      (let [type (.getColumnTypeName rsmeta idx)]
        (if (contains? schema-enums type)
          (keyword (str/replace type "_" "-") val)
          val)))))

(defn get-time []
  (Timestamp. (System/currentTimeMillis)))

(defn bcrypt [s]
  (creds/hash-bcrypt s :work-factor (:bcrypt-work-factor (config))))

(defn- q
  ([db query-data]
   (q db query-data nil))
  ([db query-data opts]
   #_(clojure.pprint/pprint (hsql/format query-data {:quoted true}))
   (jdbc/query db (hsql/format query-data {:quoted true}) opts)))

(defn- execute!
  [db query-data]
  (jdbc/execute! db (hsql/format query-data {:quoted true})))

(defn find-user
  [db username]
  (q db
     {:select :*
      :from :users
      :where [:= :user username]
      :limit 1}
     {:result-set-fn first}))

(defn find-user-by-user-or-email
  [db username-or-email]
  (q db
     {:select :*
      :from :users
      :where [:or
              [:= :user username-or-email]
              [:= :email username-or-email]]
      :limit 1}
     {:result-set-fn first}))

(defn find-user-by-email-in
  [db emails]
  (q db
     {:select :*
      :from :users
      :where [:in :email emails]
      :limit 1}
     {:result-set-fn first}))

(defn find-user-by-password-reset-code
  [db reset-code]
  (q db
     {:select :*
      :from :users
      :where [:and
              [:= :password_reset_code reset-code]
              [:>= :password_reset_code_created_at
               (-> 1 time/days time/ago time.coerce/to-sql-date)]]
      :limit 1}
     {:result-set-fn first}))

(defn find-user-by-id
  [db id]
  (q db
     {:select :*
      :from :users
      :where [:= :id id]
      :limit 1}
     {:result-set-fn first}))

(defn find-user-tokens-by-username
  [db username]
  (q db
     {:select :*
      :from :deploy_tokens
      :where [:= :user_id {:select :id
                           :from :users
                           :where [:= :user username]
                           :limit 1}]
      :order-by [[:last_used :desc]
                 [:disabled :desc]
                 [:created :desc]]}))

(defn find-token
  [db token-id]
  (q db
     {:select :*
      :from :deploy_tokens
      :where [:= :id token-id]
      :limit 1}
     {:result-set-fn first}))

(def hash-deploy-token
  (comp buddy.codecs/bytes->hex buddy.hash/sha256))

(defn find-token-by-value
  "Finds a token with the matching value. This is somewhat expensive,
  since it looks up the token by hash, then brcypt-verifies each result."
  [db token-value]
  (q db
     {:select :*
      :from :deploy_tokens
      :where [:= :token_hash (hash-deploy-token token-value)]}
     {:result-set-fn (partial filter-some
                              #(creds/bcrypt-verify token-value (:token %)))}))

(defn find-group-verification
  [db group-name]
  (q db
     {:select :*
      :from :group_verifications
      :where [:= :group_name group-name]
      :limit 1}
     {:result-set-fn first}))

(defn find-group-verifications-for-users-groups
  [db username]
  (q db
     {:select :*
      :from :group_verifications
      :where [:in :group_name
              {:select :name
               :from :groups
               :where [:and
                       [:= :user username]
                       [:not [:is :inactive true]]]}]}))

(defn verify-group!
  [db username group-name]
  (when-not (find-group-verification db group-name)
    (jdbc/insert! db :group_verifications
                  {:group_name group-name
                   :verified_by username})))

(defn find-groupnames
  [db username]
  (q db
     {:select :name
      :from :groups
      :where [:and
              [:= :user username]
              [:not [:is :inactive true]]]
      :order-by :name}
     {:row-fn :name}))

(defn group-membernames
  [db groupname]
  (q db
     {:select :user
      :from :groups
      :where [:and
              [:= :name groupname]
              [:not [:is :inactive true]]
              [:not [:is :admin true]]]}
     {:row-fn :user}))

(defn group-adminnames
  [db groupname]
  (q db
     {:select :user
      :from :groups
      :where [:and
              [:= :name groupname]
              [:not [:is :inactive true]]
              [:= :admin true]]}
     {:row-fn :user}))

(defn group-admin-emails
  [db groupname]
  (q db
     {:select :email
      :from :users
      :where [:in :user
              {:select :user
               :from :groups
               :where [:and
                       [:= :name groupname]
                       [:not [:is :inactive true]]
                       [:= :admin true]]}]}
     {:row-fn :email}))

(defn group-activenames
  [db groupname]
  (q db
     {:select :user
      :from :groups
      :where [:and
              [:= :name groupname]
              [:not [:is :inactive true]]]}
     {:row-fn :user}))

(defn group-active-users
  [db groupname]
  (q db
     {:select :*
      :from :users
      :where [:in :user
              {:select :user
               :from :groups
               :where [:and
                       [:= :name groupname]
                       [:not [:is :inactive true]]]}]}))

(defn group-allnames
  [db groupname]
  (q db
     {:select :user
      :from :groups
      :where [:= :name groupname]}
     {:row-fn :user}))

(defn group-actives
  [db groupname]
  (q db
     {:select [:user :admin]
      :from :groups
      :where [:and
              [:= :name groupname]
              [:not [:is :inactive true]]]}))

(defn jars-by-username
  [db username]
  (q db
     {:select :j/*
      :from [[:jars :j]]
      :join [[{:select [:group_name :jar_name [[:max :created] :created]]
               :from :jars
               :where [:= :user username]
               :group-by [:group_name :jar_name]}
              :l]
             [:and
              [:= :j/group_name :l/group_name]
              [:= :j/jar_name :l/jar_name]
              [:= :j/created :l/created]]]
      :order-by [[:j/group_name :asc] [:j/jar_name :asc]]}))

(defn jars-by-groupname
  [db groupname]
  (q db
     {:select :j/*
      :from [[:jars :j]]
      :join [[{:select [:jar_name [[:max :created] :created]]
               :from :jars
               :where [:= :group_name groupname]
               :group-by [:group_name :jar_name]}
              :l]
             [:and
              [:= :j/jar_name :l/jar_name]
              [:= :j/created :l/created]]]
      :order-by [[:j/group_name :asc] [:j/jar_name :asc]]}))

(defn jars-by-groups-for-username
  [db username]
  (q db
     {:select :j/*
      :from [[:jars :j]]
      :join [[{:select [:jar_name [[:max :created] :created]]
               :from :jars
               :where [:in :group_name
                       {:select :name
                        :from :groups
                        :where [:and
                                [:= :user username]
                                [:not [:is :inactive true]]]}]
               :group-by [:group_name :jar_name]}
              :l]
             [:and
              [:= :j/jar_name :l/jar_name]
              [:= :j/created :l/created]]]
      :order-by [[:j/group_name :asc] [:j/jar_name :asc]]}))

(defn recent-versions
  ([db groupname jarname]
   (recent-versions db groupname jarname nil))
  ([db groupname jarname num]
   (q db
      (cond-> {:select :version
               :from [[{:select-distinct-on [[:version] :version :created]
                        :from :jars
                        :where
                        [:and
                         [:= :group_name groupname]
                         [:= :jar_name jarname]]}
                       :distinct_jars]]
               :order-by [[:distinct_jars/created :desc]]}
        num (assoc :limit num))
      {:row-fn #(select-keys % [:version])})))

(defn count-versions
  [db groupname jarname]
  (q db
     {:select [[[:count [:distinct :version]] :count]]
      :from :jars
      :where [:and
              [:= :group_name groupname]
              [:= :jar_name jarname]]
      :limit 1}
     {:result-set-fn first
      :row-fn :count}))

(defn max-jars-id
  [db]
  (q db
     {:select [[[:max :id] :max_id]]
      :from :jars
      :limit 1}
     {:row-fn :max_id
      :result-set-fn first}))

(defn recent-jars
  [db]
  (q db
     {:select :j/*
      :from [[:jars :j]]
      :join [[{:select [:group_name :jar_name [[:max :created] :created]]
               :from :jars
               :group-by [:group_name :jar_name]
               :order-by [[:created :desc]]
               :limit 6}
              :l]
             [:and
              [:= :j/group_name :l/group_name]
              [:= :j/jar_name :l/jar_name]
              [:= :j/created :l/created]]]
      :order-by [[:l/created :desc]]
      :limit 6}))

(defn jar-exists
  [db groupname jarname]
  (q db
     {:select 1
      :from :jars
      :where [:and
              [:= :group_name groupname]
              [:= :jar_name jarname]]}
     {:result-set-fn first
      :row-fn some?}))

(def str-map
  (s/map-of keyword? string?))

(def str-map-vector
  (s/or :empty? empty?
        :str-maps (s/coll-of str-map)))

(defn- safe-edn-read
  "Reads edn data, then assures it matches the given spec, returning nil otherwise.
  This is used to prevent XSS in case there is some avenue to get
  hiccup data structures into the :license or :scm fields in the db."
  [s spec]
  (let [m (edn/read-string s)]
    (when (s/valid? spec m)
      m)))

;; public so we can override in tests
(defn safe-pr-str
  "Checks v against the given spec, throwing if it isn't valid. If it is
  valid, v is passed through pr-str. This is used to prevent XSS as
  hiccup data structures from getting into the db."
  [v spec]
  (pr-str (s/assert* spec v)))

(let [read-edn-fields #(some-> %
                               (update :licenses safe-edn-read str-map-vector)
                               (update :scm safe-edn-read str-map))]
  (defn find-jar
    ([db groupname jarname]
     (find-jar db groupname jarname nil))
    ([db groupname jarname version]
     (read-edn-fields
      (q db
         {:select :*
          :from :jars
          :where (cond-> [:and
                          [:= :group_name groupname]
                          [:= :jar_name jarname]]
                   version (conj [:= :version version]))
          :order-by (if version
                      [[:created :desc]]
                      [[[:like :version [:inline "%-SNAPSHOT"]] :asc]
                       [:created :desc]])
          :limit 1}
         {:result-set-fn first}))))

  (defn all-jars [db]
    (map read-edn-fields
         (q db {:select   :*
                :from     :jars
                :order-by :id})))

  (defn find-latest-release
    [db groupname jarname]
    (read-edn-fields
     (q db
        {:select :*
         :from :jars
         :where [:and
                 [:= :group_name groupname]
                 [:= :jar_name jarname]]
         :order-by [[:created :desc]]
         :limit 1}
        {:result-set-fn first}))))

(defn find-dependencies
  [db groupname jarname version]
  (q db
     {:select :*
      :from :deps
      :where [:and
              [:= :group_name groupname]
              [:= :jar_name jarname]
              [:= :version version]]}))

(defn find-jar-dependents
  [db groupname jarname]
  (q db
     {:select :*
      :from :deps
      :where [:and
              [:= :dep_group_name groupname]
              [:= :dep_jar_name jarname]]
      :order-by [[:id :desc]]}))

(defn all-projects
  [db offset-num limit-num]
  (q db
     {:select-distinct [:group_name :jar_name]
      :from :jars
      :order-by [[:group_name :asc] [:jar_name :asc]]
      :limit limit-num
      :offset offset-num}))

(defn count-all-projects
  [db]
  (q db
     {:select [[[:count :*] :count]]
      :from [[{:select-distinct [:group_name :jar_name]
               :from :jars}
              :sub]]}
     {:result-set-fn first
      :row-fn :count}))

(defn count-projects-before [db s]
  (q db
     {:select [[[:count :*] :count]]
      :from [[{:select-distinct [:group_name :jar_name]
               :from :jars
               :order-by [:group_name :jar_name]}
              :sub]]
      :where [[:< [:raw "group_name || '/' || jar_name"] s]]}
     {:result-set-fn first
      :row-fn :count}))

(defn browse-projects [db current-page per-page]
  (vec
   (map
    (fn [{:keys [group_name jar_name]}]
      (find-jar db group_name jar_name))
    (all-projects db
                  (* (dec current-page) per-page)
                  per-page))))

(defn- add-member*
  [db groupname username added-by admin?]
  (jdbc/insert! db :groups
                {:name      groupname
                 "\"user\"" username
                 :added_by  added-by
                 :admin     (boolean admin?)}))

(defn add-user
  [db email username password]
  (let [record {:email email
                :username username
                :password (bcrypt password)
                :send_deploy_emails true
                :created (get-time)}]
    (jdbc/insert! db :users (set/rename-keys record {:username "\"user\""}))
    (doseq [groupname [(str "net.clojars." username)
                       (str "org.clojars." username)]]
      (add-member* db groupname username "clojars" true)
      (verify-group! db username groupname))
    record))

(defn update-user
  [db account email username password]
  (let [fields {:email    email
                :username username
                :account  account}]
    (jdbc/update! db :users
                  (cond-> fields
                    true           (set/rename-keys {:username "\"user\""})
                    true           (dissoc :account)
                    (seq password) (assoc :password (bcrypt password)))
                  ["\"user\" = ?" account])
    fields))

(defn update-user-notifications
  [db account prefs]
  (jdbc/update! db :users prefs
                ["\"user\" = ?" account]))

(defn reset-user-password
  [db username reset-code password]
  (assert (not (str/blank? reset-code)))
  (assert (some? username))
  (jdbc/update! db :users
                {:password                       (bcrypt password)
                 :password_reset_code            nil
                 :password_reset_code_created_at nil}
                ["password_reset_code = ? AND \"user\" = ?"
                 reset-code username]))

;; Password resets
;; Reference:
;; https://github.com/xavi/noir-auth-app/blob/master/src/noir_auth_app/models/user.clj
;; https://github.com/weavejester/crypto-random/blob/master/src/crypto/random.clj
;; https://jira.atlassian.com/browse/CWD-1897?focusedCommentId=196759&page=com.atlassian.jira.plugin.system.issuetabpanels:comment-tabpanel#comment-196759
(defn generate-secure-token [size]
  (let [seed (byte-array size)]
    ;; http://docs.oracle.com/javase/6/docs/api/java/security/SecureRandom.html
    (.nextBytes (SecureRandom/getInstance "SHA1PRNG") seed)
    seed))

(defn hexadecimalize [byte-array]
  ;; converts byte array to hex string
  ;; http://stackoverflow.com/a/8015558/974795
  (str/lower-case (apply str (map #(format "%02X" %) byte-array))))

(defn set-password-reset-code!
  [db username]
  (let [reset-code (hexadecimalize (generate-secure-token 20))]
    (jdbc/update! db :users
                  {:password_reset_code            reset-code
                   :password_reset_code_created_at (get-time)}
                  ["\"user\" = ?" username])
    reset-code))

(defn set-otp-secret-key!
  [db username]
  (let [secret-key (ot/generate-secret-key)]
    (jdbc/update! db :users
                  {:otp_secret_key secret-key}
                  ["\"user\" = ?" username])))

(defn enable-otp!
  [db username]
  (let [recovery-code (str (UUID/randomUUID))]
    (jdbc/update! db :users
                  {:otp_active        true
                   :otp_recovery_code (bcrypt recovery-code)}
                  ["\"user\" = ?" username])
    recovery-code))

(defn disable-otp!
  [db username]
  (jdbc/update! db :users
                {:otp_active        false
                 :otp_recovery_code nil
                 :otp_secret_key    nil}
                ["\"user\" = ?" username]))

(defn user-has-mfa?
  [db username]
  (some? (:otp_recovery_code (find-user db username))))

(defn generate-deploy-token []
  (str "CLOJARS_" (hexadecimalize (generate-secure-token 30))))

(defn is-deploy-token?
  "Returns true if the value is token shaped."
  [v]
  (boolean (and v (re-find #"^CLOJARS_[0-9a-f]{60}$" v))))

(defn add-deploy-token
  [db username token-name group-name jar-name single-use? expires-at]
  (let [user (find-user db username)
        token (generate-deploy-token)
        record {:user_id (:id user)
                :name token-name
                :token token
                :token_hash (hash-deploy-token token)
                :group_name group-name
                :jar_name jar-name
                :single_use (if single-use?
                              :single-use-status/yes
                              :single-use-status/no)
                :expires_at expires-at}]
    (jdbc/insert! db :deploy_tokens
                  (update record :token bcrypt))
    record))

(defn consume-deploy-token
  [db token-id]
  (jdbc/update! db :deploy_tokens
                {:single_use :single-use-status/used
                 :updated    (get-time)}
                ["id = ?" token-id]))

(defn disable-deploy-token
  [db token-id]
  (jdbc/update! db :deploy_tokens
                {:disabled true
                 :updated  (get-time)}
                ["id = ?" token-id]))

(defn set-deploy-token-used
  [db token-id]
  (jdbc/update! db :deploy_tokens
                {:last_used (get-time)}
                ["id = ?" token-id]))

(defn set-deploy-token-hash
  [db token-id token-value]
  (jdbc/update! db :deploy_tokens
                {:token_hash (hash-deploy-token token-value)}
                ["id = ? AND token_hash is null" token-id]))

(defn add-audit [db tag username group-name jar-name version message]
  (jdbc/insert! db :audit
                {:tag        tag
                 "\"user\""  username
                 :group_name group-name
                 :jar_name   jar-name
                 :version    version
                 :message    message}))

(defn find-audit
  [db {:keys [username group-name jar-name version]}]
  (when-some [where (cond
                      version    [:and
                                  [:= :group_name group-name]
                                  [:= :jar_name jar-name]
                                  [:= :version version]]
                      jar-name   [:and
                                  [:= :group_name group-name]
                                  [:= :jar_name jar-name]]
                      group-name [:= :group_name group-name]
                      username   [:= :user username]
                      :else      nil)]
    (q db
       {:select :*
        :from :audit
        :where where
        :order-by [[:created :desc]]})))

(defn inactivate-member
  [db groupname username inactivated-by]
  (execute!
   db
   {:update :groups
    :set    {:inactive       true
             :inactivated_by inactivated-by}
    :where  [:and
             [:= :user username]
             [:= :name groupname]
             [:not [:is :inactive true]]]}))

(defn add-member
  [db groupname username added-by]
  (inactivate-member db groupname username added-by)
  (add-member* db groupname username added-by false))

(defn add-admin [db groupname username added-by]
  (inactivate-member db groupname username added-by)
  (add-member* db groupname username added-by true))

(defn check-group
  "Throws if the group does not exist, is not accessible to the account, or not verified
  (when the jarname doesn't already exist).

  We only allow deploys of new jars to verified groups. New versions of existing jars
  can still be deployed to non-verified groups."
  [db account groupname jarname]
  (let [actives (group-activenames db groupname)
        err (fn [msg]
              (throw (ex-info msg {:account account
                                   :group groupname})))]
    (cond
      ;; group exists, but user doesn't have access to it
      (and (seq actives)
           (not (some #{account} actives)))
      (err (format "You don't have access to the '%s' group" groupname))

      ;; group/jar exists, so new versions can be deployed
      (jar-exists db groupname jarname)
      true

      ;; group doesn't exist, reject since we no longer auto-create groups
      (empty? actives)
      (err (format "Group '%s' doesn't exist. See https://bit.ly/3MuKGXO" groupname))

      ;; group isn't verified, so new jars/projects can't be deployed to it
      (not (find-group-verification db groupname))
      (err (format "Group '%s' isn't verified, so can't contain new projects. See https://bit.ly/3MuKGXO" groupname)))))

(defn add-group
  "Adds a new group entry without any checks other than if it exists
  already. Intended to be used from clojars.admin and tests."
  [db account groupname]
  (let [actives (group-activenames db groupname)]
    (when (empty? actives)
      (add-admin db groupname account "clojars"))))

(defn- group-names-for-provider
  [provider provider-username]
  (for [prefix
        (case provider
          "GitHub" ["com.github."
                    "io.github."]
          "GitLab" ["com.gitlab."
                    "io.gitlab."])]
    (str prefix (str/lower-case provider-username))))

(defn maybe-verify-provider-groups
  "Will add and verify groups that are provider specific (github,
  gitlab, etc). Will only add the group if it doesn't already
  exist. Will only verify the group if it isn't already verified and
  the user is a member of the group."
  [db {:keys [auth-provider provider-login username]}]
  (when (and auth-provider
             provider-login
             username)
    (let [results
          (for [group-name (group-names-for-provider auth-provider provider-login)]
            (when (not (find-group-verification db group-name))
              (let [actives (group-activenames db group-name)]
                (cond
                  (empty? actives)
                  (do
                    (add-admin db group-name username "clojars")
                    (verify-group! db username group-name)
                    nil)

                  (some #{username} actives)
                  (do
                    (verify-group! db username group-name)
                    nil)

                  :else
                  group-name))))]
      (into []
            (comp (remove nil?)
                  (map (fn [group-name]
                         {:tag :provider-group-verification-user-not-member
                          :user username
                          :group group-name})))
            results))))

(defn- group+jar+version-where
  [group-id jar-id version]
  (let [base-where "group_name = ?"]
    (cond
      version [(format "%s AND jar_name = ? AND version = ?" base-where)
               group-id jar-id version]
      jar-id  [(format "%s AND jar_name = ?" base-where)
               group-id jar-id]
      :else   [base-where group-id])))

(defn- delete-dependencies
  [db group-id jar-id version]
  (jdbc/delete! db :deps (group+jar+version-where group-id jar-id version)))

(defn add-jar
  [db account {:keys [group name version description homepage authors packaging licenses scm dependencies]}]
  (check-group db account group name)
  (jdbc/insert! db :jars
                {:group_name  group
                 :jar_name    name
                 :version     version
                 "\"user\""   account
                 :created     (get-time)
                 :description description
                 :homepage    homepage
                 :packaging   (when packaging (clojure.core/name packaging))
                 :licenses    (when licenses (safe-pr-str licenses str-map-vector))
                 :scm         (when scm (safe-pr-str scm str-map))
                 :authors     (str/join ", " (map #(.replace % "," "")
                                                  authors))})
  (when (mvn/snapshot-version? version)
    (delete-dependencies db group name version))
  (doseq [dep dependencies]
    (jdbc/insert! db :deps
                  (-> dep
                      (set/rename-keys {:group_name :dep_group_name
                                        :jar_name   :dep_jar_name
                                        :version    :dep_version
                                        :scope      :dep_scope})
                      (assoc :group_name group
                             :jar_name   name
                             :version   version)))))

(defn- delete-jars*
  [db group-id jar-id version]
  (jdbc/delete! db :jars (group+jar+version-where group-id jar-id version)))

(defn delete-jars [db group-id & [jar-id version]]
  (delete-jars* db group-id jar-id version)
  (delete-dependencies db group-id jar-id version))

;; does not delete jars in the group. should it?
(defn delete-group
  [db group-id]
  (jdbc/delete! db :groups
                ["name = ?" group-id]))

(defn- find-groups-jars-information
  [db group-id]
  (q db
     {:select [:j/jar_name :j/group_name :homepage :description
               :user [:j/version :latest_version] [:r2/version :latest_release]]
      :from [[:jars :j]]
      ;; find the latest version
      :join [[{:select [:jar_name :group_name [[:max :created] :created]]
               :from :jars
               :where [:= :group_name group-id]
               :group-by [:group_name :jar_name]} :l]
             [:and
              [:= :j/jar_name :l/jar_name]
              [:= :j/group_name :l/group_name]
              [:= :j/created :l/created]]]

      :left-join [;; Find the created ts for latest release
                  [{:select [:jar_name :group_name [[:max :created] :created]]
                    :from :jars
                    :where [:and
                            [:= :group_name group-id]
                            [:not [:like :version [:inline "%-SNAPSHOT"]]]]
                    :group-by [:group_name :jar_name]} :r]
                  [:and
                   [:= :j/jar_name :r/jar_name]
                   [:= :j/group_name :r/group_name]]

                  ;; Find version for latest release
                  [{:select [:jar_name :group_name :version :created]
                    :from :jars
                    :where [:= :group_name group-id]} :r2]
                  [:and
                   [:= :j/jar_name :r2/jar_name]
                   [:= :j/group_name :r2/group_name]
                   [:= :r/created :r2/created]]]
      :where [:= :j/group_name group-id]
      :order-by [[:j/group_name :asc]
                 [:j/jar_name :asc]]}))

(defn- find-jars-information*
  [db group-id artifact-id]
  (q db
     {:select [:j/jar_name :j/group_name :homepage :description
               :user [:j/version :latest_version] [:r2/version :latest_release]]
      :from [[:jars :j]]
      ;; find the latest version
      :join [[{:select [:jar_name :group_name [[:max :created] :created]]
               :from :jars
               :where [:and
                       [:= :group_name group-id]
                       [:= :jar_name artifact-id]]
               :group-by [:group_name :jar_name]} :l]
             [:and
              [:= :j/jar_name :l/jar_name]
              [:= :j/group_name :l/group_name]
              [:= :j/created :l/created]]]

      :left-join [;; Find the created ts for latest release
                  [{:select [:jar_name :group_name [[:max :created] :created]]
                    :from :jars
                    :where [:and
                            [:= :group_name group-id]
                            [:= :jar_name artifact-id]
                            [:not [:like :version [:inline "%-SNAPSHOT"]]]]
                    :group-by [:group_name :jar_name]} :r]
                  [:and
                   [:= :j/jar_name :r/jar_name]
                   [:= :j/group_name :r/group_name]]

                  ;; Find version for latest release
                  [{:select [:jar_name :group_name :version :created]
                    :from :jars
                    :where [:and
                            [:= :group_name group-id]
                            [:= :jar_name artifact-id]]}
                   :r2]
                  [:and
                   [:= :j/jar_name :r2/jar_name]
                   [:= :j/group_name :r2/group_name]
                   [:= :r/created :r2/created]]]
      :where [:and
              [:= :j/group_name group-id]
              [:= :j/jar_name artifact-id]]
      :order-by [[:j/group_name :asc]
                 [:j/jar_name :asc]]}))

(defn find-jars-information
  ([db group-id]
   (find-jars-information db group-id nil))
  ([db group-id artifact-id]
   (if artifact-id
     (find-jars-information* db group-id artifact-id)
     (find-groups-jars-information db group-id))))

(defn set-group-mfa-required
  [db group-id required?]
  (execute!
   db
   {:insert-into   :group_settings
    :values        [{:group_name            group-id
                     :require_mfa_to_deploy (boolean required?)}]
    ;; update if settings already exist
    :on-conflict   [:group_name]
    :do-update-set {:require_mfa_to_deploy (boolean required?)}}))

(defn get-group-settings
  [db group-id]
  (q db
     {:select :*
      :from :group_settings
      :where [:= :group_name group-id]
      :limit 1}
     {:result-set-fn first}))
