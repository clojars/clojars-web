(ns clojars.db
  (:require [clojure.string :as str]
            [clj-time.core :as time]
            [clj-time.coerce :as time.coerce]
            [clojars.config :refer [config]]
            [clojars.db.sql :as sql]
            [cemerick.friend.credentials :as creds])
  (:import java.util.Date
           java.security.SecureRandom
           java.util.concurrent.Executors))

(def reserved-names
  #{"clojure" "clojars" "clojar" "register" "login"
    "pages" "logout" "password" "username" "user"
    "repo" "repos" "jar" "jars" "about" "help" "doc"
    "docs" "images" "js" "css" "maven" "api"
    "download" "create" "new" "upload" "contact" "terms"
    "group" "groups" "browse" "status" "blog" "search"
    "email" "welcome" "devel" "development" "test" "testing"
    "prod" "production" "admin" "administrator" "root"
    "webmaster" "profile" "dashboard" "settings" "options"
    "index" "files" "releases" "snapshots"})

(def ^{:private true} constituent-chars
  (->> [[\a \z] [\A \Z] [\0 \9]]
       (mapcat (fn [[x y]] (range (int x) (inc (int y)))))
       (map char)
       vec))

(defn rand-string
  "Generates a random string of [A-z0-9] of length n."
  [n]
  (str/join (repeatedly n #(rand-nth constituent-chars))))

(defn get-time []
  (Date.))

(defn bcrypt [s]
  (creds/hash-bcrypt s :work-factor (:bcrypt-work-factor config)))

(defn find-user [db username]
  (sql/find-user {:username username}
                 {:connection db
                  :result-set-fn first}))

(defn find-user-by-user-or-email [db username-or-email]
  (sql/find-user-by-user-or-email {:username_or_email username-or-email}
                                  {:connection db
                                   :result-set-fn first}))

(defn find-user-by-password-reset-code [db reset-code]
  (sql/find-user-by-password-reset-code {:reset_code reset-code
                                         :reset_code_created_at
                                         (-> 1 time/days time/ago time.coerce/to-long)}
                                        {:connection db
                                         :result-set-fn first}))

(defn find-groupnames [db username]
  (sql/find-groupnames {:username username}
                       {:connection db
                        :row-fn :name}))

(defn group-membernames [db groupname]
  (sql/group-membernames {:groupname groupname}
                         {:connection db
                          :row-fn :user}))

(defn group-keys [db groupname]
  (sql/group-keys {:groupname groupname}
                  {:connection db
                   :row-fn :pgp_key}))

(defn jars-by-username [db username]
  (sql/jars-by-username {:username username}
                        {:connection db}))

(defn jars-by-groupname [db groupname]
  (sql/jars-by-groupname {:groupname groupname}
                         {:connection db}))

(defn recent-versions
  ([db groupname jarname]
   (sql/recent-versions {:groupname groupname
                         :jarname jarname}
                        {:connection db}))
  ([db groupname jarname num]
   (sql/recent-versions-limit {:groupname groupname
                               :jarname jarname
                               :num num}
                              {:connection db})))

(defn count-versions [db groupname jarname]
  (sql/count-versions {:groupname groupname
                       :jarname jarname}
                      {:connection db
                       :result-set-fn first
                       :row-fn :count}))

(defn recent-jars [db]
  (sql/recent-jars {} {:connection db}))

(defn jar-exists [db groupname jarname]
  (sql/jar-exists {:groupname groupname
                   :jarname jarname}
                  {:connection db
                   :result-set-fn first
                   :row-fn #(= % 1)}))

(defn find-jar
  ([db groupname jarname]
   (sql/find-jar {:groupname groupname
                  :jarname jarname}
                 {:connection db
                  :result-set-fn first}))
  ([db groupname jarname version]
   (sql/find-jar-versioned {:groupname groupname
                            :jarname jarname
                            :version version}
                           {:connection db
                            :result-set-fn first})))

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

(def write-executor (memoize #(Executors/newSingleThreadExecutor)))

(def ^:private ^:dynamic *in-executor* nil)

(defn serialize-task* [task-name task]
  (if *in-executor*
    (task)
    (binding [*in-executor* true]
      (let [bound-f (bound-fn []
                      (try
                        (task)
                        (catch Throwable e
                          e)))
            response (deref
                      (.submit (write-executor) bound-f)
                      10000 ::timeout)]
        (cond
          (= response ::timeout) (throw
                                  (ex-info
                                   "Timed out waiting for serialized task to run"
                                   {:name task-name}))
          (instance? Throwable response) (throw
                                          (ex-info "Serialized task failed"
                                                   {:name task-name}
                                                   response))
          :default response)))))

(defmacro serialize-task [name & body]
  `(serialize-task* ~name
                    (fn [] ~@body)))

(defn add-user [db email username password pgp-key]
  (let [record {:email email, :username username, :password (bcrypt password),
                :pgp_key pgp-key :created (get-time)}
        groupname (str "org.clojars." username)]
    (serialize-task :add-user
                    (sql/insert-user! record
                                      {:connection db})
                    (sql/insert-group! {:groupname groupname :username username}
                                       {:connection db}))
    record))

(defn update-user [db account email username password pgp-key]
  (let [fields {:email email
                :username username
                :pgp_key pgp-key
                :account account}]
    (serialize-task :update-user
                    (if (empty? password)
                      (sql/update-user! fields {:connection db})
                      (sql/update-user-with-password!
                        (assoc fields :password
                                      (bcrypt password))
                        {:connection db})))
    fields))

(defn reset-user-password [db username reset-code password]
  (assert (not (str/blank? reset-code)))
  (assert (some? username))
  (serialize-task :reset-user-password
                    (sql/reset-user-password! {:password (bcrypt password)
                                               :reset_code reset-code
                                               :username username}
                                               {:connection db})))

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
                                        ; converts byte array to hex string
                                        ; http://stackoverflow.com/a/8015558/974795
  (str/lower-case (apply str (map #(format "%02X" %) byte-array))))

(defn set-password-reset-code! [db username]
  (let [reset-code (hexadecimalize (generate-secure-token 20))]
    (serialize-task :set-password-reset-code
                    (sql/set-password-reset-code! {:reset_code reset-code
                                                   :reset_code_created_at (get-time)
                                                   :username username}
                                                  {:connection db}))
    reset-code))

(defn add-member [db groupname username added-by]
  (serialize-task :add-member
                  (sql/add-member! {:groupname groupname
                                    :username username
                                    :added_by added-by}
                                   {:connection db})))

(defn check-and-add-group [db account groupname]
  (when-not (re-matches #"^[a-z0-9-_.]+$" groupname)
    (throw (Exception. (str "Group names must consist of lowercase "
                            "letters, numbers, hyphens, underscores "
                            "and full-stops."))))
  (let [members (group-membernames db groupname)]
    (if (empty? members)
      (if (reserved-names groupname)
        (throw (Exception. (str "The group name "
                                groupname
                                " is already taken.")))
        (add-member db groupname account "clojars"))
      (when-not (some #{account} members)
        (throw (Exception. (str "You don't have access to the "
                                groupname " group.")))))))

(defn add-jar [db account {:keys [group name version
                               description homepage authors]}]
  (check-and-add-group db account group)
  (serialize-task :add-jar
                  (sql/add-jar! {:groupname group
                                 :jarname   name
                                 :version   version
                                 :user      account
                                 :created    (get-time)
                                 :description description
                                 :homepage   homepage
                                 :authors    (str/join ", " (map #(.replace % "," "")
                                                                 authors))}
                                {:connection db})))

(defn delete-jars [db group-id & [jar-id version]]
  (serialize-task :delete-jars
                  (let [coords {:group_id group-id}]
                    (if jar-id
                      (let [coords (assoc coords :jar_id jar-id)]
                        (if version
                          (sql/delete-jar-version! (assoc coords :version version)
                                                   {:connection db})
                          (sql/delete-jars! coords
                                            {:connection db})))
                      (sql/delete-groups-jars! coords
                                        {:connection db})))))

;; does not delete jars in the group. should it?
(defn delete-groups [db group-id]
  (serialize-task :delete-groups
                  (sql/delete-group! {:group_id group-id}
                                     {:connection db})))

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

(defn promote [db group name version]
  (serialize-task :promote
                  (sql/promote! {:group_id group
                                 :artifact_id name
                                 :version version
                                 :promoted_at (get-time)}
                                {:connection db})))

(defn promoted? [db group-id artifact-id version]
  (sql/promoted {:group_id group-id
                 :artifact_id artifact-id
                 :version version}
                {:connection db
                 :result-set-fn first
                 :row-fn :promoted_at}))
