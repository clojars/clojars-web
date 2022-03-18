(ns clojars.db
  (:require
   [buddy.core.codecs :as buddy.codecs]
   [buddy.core.hash :as buddy.hash]
   [cemerick.friend.credentials :as creds]
   [clj-time.coerce :as time.coerce]
   [clj-time.core :as time]
   [clojars.config :refer [config]]
   [clojure.java.jdbc :as jdbc]
   [clojars.db.sql :as sql]
   [clojars.maven :as mvn]
   [clojars.util :refer [filter-some]]
   [clojure.edn :as edn]
   [clojure.set :as set]
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
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
    "webmaster"
    "welcome"})

;; ----- JDBC extensions -----

;; read/write postgres enums
;; from https://www.bevuta.com/en/blog/using-postgresql-enums-in-clojure/

(defn- kw->pgenum [kw]
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
    (result-set-read-column [val rsmeta idx]
      (let [type (.getColumnTypeName rsmeta idx)]
        (if (contains? schema-enums type)
          (keyword (str/replace type "_" "-") val)
          val)))))

(defn get-time []
  (Timestamp. (System/currentTimeMillis)))

(defn bcrypt [s]
  (creds/hash-bcrypt s :work-factor (:bcrypt-work-factor (config))))

(defn find-user [db username]
  (sql/find-user {:username username}
                 {:connection db
                  :result-set-fn first}))

(defn find-user-by-user-or-email [db username-or-email]
  (sql/find-user-by-user-or-email {:username_or_email username-or-email}
                                  {:connection db
                                   :result-set-fn first}))

(defn find-user-by-email-in [db emails]
  (sql/find-user-by-email-in {:email emails}
                             {:connection db
                              :result-set-fn first}))

(defn find-user-by-password-reset-code [db reset-code]
  (sql/find-user-by-password-reset-code {:reset_code reset-code
                                         :reset_code_created_at
                                         (-> 1 time/days time/ago time.coerce/to-sql-date)}
                                        {:connection db
                                         :result-set-fn first}))

(defn find-user-by-id [db id]
  (sql/find-user-by-id {:id id}
                       {:connection db
                        :result-set-fn first}))

(defn find-user-tokens-by-username [db username]
  (sql/find-user-tokens-by-username {:username username}
                                    {:connection db}))

(defn find-token [db token-id]
  (sql/find-token {:id token-id}
                  {:connection db
                   :result-set-fn first}))

(def hash-deploy-token
  (comp buddy.codecs/bytes->hex buddy.hash/sha256))

(defn find-token-by-value
  "Finds a token with the matching value. This is somewhat expensive,
  since it looks up the token by hash, then brcypt-verifies each result."
  [db token-value]
  (sql/find-tokens-by-hash
   {:token_hash (hash-deploy-token token-value)}
   {:connection db
    :result-set-fn (partial filter-some
                            #(creds/bcrypt-verify token-value (:token %)))}))

(defn find-group-verification [db group-name]
  (sql/find-group-verification {:group_name group-name}
                               {:connection db
                                :result-set-fn first}))

(defn verify-group! [db username group-name]
  (when-not (find-group-verification db group-name)
    (sql/verify-group! {:group_name group-name
                        :verifying_username username}
                       {:connection db})))

(defn find-groupnames [db username]
  (sql/find-groupnames {:username username}
                       {:connection db
                        :row-fn :name}))

(defn group-membernames [db groupname]
  (sql/group-membernames {:groupname groupname}
                         {:connection db
                          :row-fn :user}))

(defn group-adminnames [db groupname]
  (sql/group-adminnames {:groupname groupname}
                        {:connection db
                         :row-fn :user}))

(defn group-activenames [db groupname]
  (sql/group-activenames {:groupname groupname}
                         {:connection db
                          :row-fn :user}))

(defn group-active-users [db groupname]
  (sql/group-active-users {:groupname groupname}
                          {:connection db}))

(defn group-allnames [db groupname]
  (sql/group-actives {:groupname groupname}
                     {:connection db
                      :row-fn :user}))

(defn group-actives [db groupname]
  (sql/group-actives {:groupname groupname}
                     {:connection db}))

(defn jars-by-username [db username]
  (sql/jars-by-username {:username username}
                        {:connection db}))

(defn jars-by-groupname [db groupname]
  (sql/jars-by-groupname {:groupname groupname}
                         {:connection db}))

(defn jars-by-groups-for-username [db username]
  (sql/jars-by-groups-for-username {:username username}
                                   {:connection db}))

(defn recent-versions
  ([db groupname jarname]
   (sql/recent-versions {:groupname groupname
                         :jarname jarname}
                        {:connection db
                         :row-fn #(select-keys % [:version])}))
  ([db groupname jarname num]
   (sql/recent-versions-limit {:groupname groupname
                               :jarname jarname
                               :num num}
                              {:connection db
                               :row-fn #(select-keys % [:version])})))

(defn count-versions [db groupname jarname]
  (sql/count-versions {:groupname groupname
                       :jarname jarname}
                      {:connection db
                       :result-set-fn first
                       :row-fn :count}))

(defn max-jars-id
  [db]
  (sql/max-jars-id {} {:connection db
                       :row-fn :max_id
                       :result-set-fn first}))

(defn recent-jars [db]
  (sql/recent-jars {} {:connection db}))

(defn jar-exists [db groupname jarname]
  (sql/jar-exists {:groupname groupname
                   :jarname jarname}
                  {:connection db
                   :result-set-fn first
                   :row-fn :exist}))

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
     (read-edn-fields
      (sql/find-jar {:groupname groupname
                     :jarname   jarname}
                    {:connection    db
                     :result-set-fn first})))
    ([db groupname jarname version]
     (read-edn-fields
      (sql/find-jar-versioned {:groupname groupname
                               :jarname   jarname
                               :version   version}
                              {:connection    db
                               :result-set-fn first}))))
  (defn all-jars [db]
    (map read-edn-fields
         (sql/all-jars {} {:connection db}))))

(defn find-dependencies
  [db groupname jarname version]
  (sql/find-dependencies {:groupname groupname
                          :jarname   jarname
                          :version   version}
                         {:connection db}))

(defn find-jar-dependents
  [db groupname jarname]
  (sql/find-jar-dependents {:groupname groupname
                            :jarname   jarname}
                           {:connection db}))

(defn all-projects [db offset-num limit-num]
  (sql/all-projects {:num limit-num
                     :offset offset-num}
                    {:connection db}))

(defn count-all-projects [db]
  (sql/count-all-projects {}
                          {:connection db
                           :result-set-fn first
                           :row-fn :count}))

(defn count-projects-before [db s]
  (sql/count-projects-before {:s s}
                             {:connection db
                              :result-set-fn first
                              :row-fn :count}))

(defn browse-projects [db current-page per-page]
  (vec
   (map
    (fn [{:keys [group_name jar_name]}]
      (find-jar db group_name jar_name))
    (all-projects db
                  (* (dec current-page) per-page)
                  per-page))))

(defn add-user [db email username password]
  (let [record {:email email
                :username username
                :password (bcrypt password)
                :send_deploy_emails true
                :created (get-time)}]
    (sql/insert-user! record
                      {:connection db})
    (doseq [groupname [(str "net.clojars." username)
                       (str "org.clojars." username)]]
      (sql/add-member! {:groupname groupname
                        :username username
                        :admin true
                        :added_by "clojars"}
                       {:connection db})
      (verify-group! db username groupname))
    record))

(defn update-user [db account email username password]
  (let [fields {:email email
                :username username
                :account account}]
    (if (empty? password)
      (sql/update-user! fields {:connection db})
      (sql/update-user-with-password!
       (assoc fields :password
              (bcrypt password))
       {:connection db}))
    fields))

(defn update-user-notifications [db account prefs]
  (sql/update-user-notifications! (assoc prefs :account account)
                                  {:connection db}))

(defn reset-user-password [db username reset-code password]
  (assert (not (str/blank? reset-code)))
  (assert (some? username))
  (sql/reset-user-password! {:password (bcrypt password)
                             :reset_code reset-code
                             :username username}
                            {:connection db}))

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

(defn set-password-reset-code! [db username]
  (let [reset-code (hexadecimalize (generate-secure-token 20))]
    (sql/set-password-reset-code! {:reset_code reset-code
                                   :reset_code_created_at (get-time)
                                   :username username}
                                  {:connection db})
    reset-code))

(defn set-otp-secret-key! [db username]
  (let [secret-key (ot/generate-secret-key)]
    (sql/set-otp-secret-key! {:otp_secret_key secret-key
                              :username username}
                             {:connection db})))

(defn enable-otp! [db username]
  (let [recovery-code (str (UUID/randomUUID))]
    (sql/enable-otp! {:otp_recovery_code (bcrypt recovery-code)
                      :username username}
                     {:connection db})
    recovery-code))

(defn disable-otp! [db username]
  (sql/disable-otp! {:username username}
                    {:connection db}))

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
    (sql/insert-deploy-token<! (update record :token bcrypt)
                               {:connection db})
    record))

(defn consume-deploy-token [db token-id]
  (sql/consume-deploy-token!
   {:token_id token-id
    :updated (get-time)}
   {:connection db}))

(defn disable-deploy-token [db token-id]
  (sql/disable-deploy-token!
   {:token_id token-id
    :updated (get-time)}
   {:connection db}))

(defn set-deploy-token-used [db token-id]
  (sql/set-deploy-token-used!
   {:token_id token-id
    :timestamp (get-time)}
   {:connection db}))

(defn set-deploy-token-hash [db token-id token-value]
  (sql/set-deploy-token-hash!
   {:token_id token-id
    :token_hash (hash-deploy-token token-value)}
   {:connection db}))

(defn add-audit [db tag username group-name jar-name version message]
  (sql/add-audit!
   {:tag tag
    :user username
    :groupname group-name
    :jarname jar-name
    :version version
    :message message}
   {:connection db}))

(defn find-audit
  [db {:as args :keys [username group-name jar-name version]}]
  (when-some [f (cond
                  version    sql/find-audit-for-version
                  jar-name   sql/find-audit-for-jar
                  group-name sql/find-audit-for-group
                  username   sql/find-audit-for-user
                  :else      nil)]
    (f (set/rename-keys
        args
        ;; I wish we were consistent with naming :(
        {:username   :user
         :group-name :groupname
         :jar-name   :jarname})
       {:connection db})))

(defn add-member [db groupname username added-by]
  (sql/inactivate-member! {:groupname groupname
                           :username username
                           :inactivated_by added-by}
                          {:connection db})
  (sql/add-member! {:groupname groupname
                    :username username
                    :admin false
                    :added_by added-by}
                   {:connection db}))

(defn add-admin [db groupname username added-by]
  (sql/inactivate-member! {:groupname groupname
                           :username username
                           :inactivated_by added-by}
                          {:connection db})
  (sql/add-member! {:groupname groupname
                    :username username
                    :admin true
                    :added_by added-by}
                   {:connection db}))

(defn inactivate-member [db groupname username inactivated-by]
  (sql/inactivate-member! {:groupname groupname
                           :username username
                           :inactivated_by inactivated-by}
                          {:connection db}))

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
      (err (format "Group '%s' doesn't exist (see https://git.io/JOs8J)" groupname))

      ;; group isn't verified, so new jars/projects can't be deployed to it
      (not (find-group-verification db groupname))
      (err (format "Group '%s' isn't verified, so can't contain new projects (see https://git.io/JOs8J)" groupname)))))

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


(defn add-jar [db account {:keys [group name version description homepage authors packaging licenses scm dependencies]}]
  (check-group db account group name)
  (sql/add-jar! {:groupname   group
                 :jarname     name
                 :version     version
                 :user        account
                 :created     (get-time)
                 :description description
                 :homepage    homepage
                 :packaging   (when packaging (clojure.core/name packaging))
                 :licenses    (when licenses (safe-pr-str licenses str-map-vector))
                 :scm         (when scm (safe-pr-str scm str-map))
                 :authors     (str/join ", " (map #(.replace % "," "")
                                                  authors))}
                {:connection db})
  (when (mvn/snapshot-version? version)
    (sql/delete-dependencies-version!
     {:group_id group
      :jar_id name
      :version version}
     {:connection db}))
  (doseq [dep dependencies]
    (sql/add-dependency! (-> dep
                             (set/rename-keys {:group_name :dep_groupname
                                               :jar_name   :dep_jarname
                                               :version    :dep_version
                                               :scope      :dep_scope})
                             (assoc :groupname group
                                    :jarname   name
                                    :version   version))
                         {:connection db})))

(defn delete-jars [db group-id & [jar-id version]]
  (let [coords {:group_id group-id}]
    (if jar-id
      (let [coords (assoc coords :jar_id jar-id)]
        (if version
          (let [coords' (assoc coords :version version)]
            (sql/delete-jar-version! coords'
                                     {:connection db})
            (sql/delete-dependencies-version! coords'
                                              {:connection db}))
          (do
            (sql/delete-jars! coords
                              {:connection db})
            (sql/delete-dependencies! coords
                                      {:connection db}))))
      (do
        (sql/delete-groups-jars! coords
                                 {:connection db})
        (sql/delete-groups-dependencies! coords
                                         {:connection db})))))

;; does not delete jars in the group. should it?
(defn delete-groups [db group-id]
  (sql/delete-group! {:group_id group-id}
                     {:connection db}))

(defn find-jars-information
  ([db group-id]
   (find-jars-information db group-id nil))
  ([db group-id artifact-id]
   (if artifact-id
     (sql/find-jars-information {:group_id group-id
                                 :artifact_id artifact-id}
                                {:connection db})
     (sql/find-groups-jars-information {:group_id group-id}
                                       {:connection db}))))
