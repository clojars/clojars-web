(ns clojars.db
  (:require
   [buddy.core.codecs :as buddy.codecs]
   [buddy.core.hash :as buddy.hash]
   [cemerick.friend.credentials :as creds]
   [clj-time.coerce :as time.coerce]
   [clj-time.core :as time]
   [clojars.config :refer [config]]
   [clojars.db.sql :as sql]
   [clojars.maven :as mvn]
   [clojars.util :refer [filter-some]]
   [clojure.edn :as edn]
   [clojure.set :as set]
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [one-time.core :as ot])
  (:import
   (java.security SecureRandom)
   (java.sql Timestamp)
   (java.util UUID)))

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
    #(find-jar db (:group_name %) (:jar_name %))
    (all-projects db
                  (* (dec current-page) per-page)
                  per-page))))

(defn add-user [db email username password]
  (let [record {:email email, :username username, :password (bcrypt password),
                :created (get-time)}
        groupname (str "org.clojars." username)]
    (sql/insert-user! record
                      {:connection db})
    (sql/add-member! {:groupname groupname
                      :username username
                      :admin true
                      :added_by "clojars"}
                     {:connection db})
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

(defn add-deploy-token [db username token-name group-name jar-name]
  (let [user (find-user db username)
        token (generate-deploy-token)
        record {:user_id (:id user)
                :name token-name
                :token token
                :token_hash (hash-deploy-token token)
                :group_name group-name
                :jar_name jar-name}]
    (sql/insert-deploy-token! (update record :token bcrypt)
                              {:connection db})
    record))

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
  "Throws if the group is invalid or not accessible to the account"
  [actives account groupname]
  (let [err (fn [msg]
              (throw (ex-info msg {:account account
                                   :group groupname})))]
    (when (reserved-names groupname)
      (err (format "The group name '%s' is reserved" groupname)))
    (when (and (seq actives)
               (not (some #{account} actives)))
      (err (format "You don't have access to the '%s' group" groupname)))))

(defn check-and-add-group [db account groupname]
  (let [actives (group-activenames db groupname)]
    (check-group actives account groupname)
    (when (empty? actives)
      (add-admin db groupname account "clojars"))))

(defn add-jar [db account {:keys [group name version description homepage authors packaging licenses scm dependencies]}]
  (check-and-add-group db account group)
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

