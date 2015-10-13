(ns clojars.db
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clj-time.core :as time]
            [clj-time.coerce :as time.coerce]
            [clojars.config :refer [config]]
            [korma.db :refer [defdb transaction rollback]]
            [korma.core :refer [defentity select group fields order join
                                modifier exec-raw where limit values with
                                has-many raw insert update delete set-fields
                                offset]]
            [cemerick.friend.credentials :as creds])
  (:import java.security.MessageDigest
           java.util.Date
           java.security.SecureRandom
           java.io.File
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

(defdb mydb (:db config))
(defentity users)
(defentity groups)
(defentity jars)

;; über-hack to work around hard-coded sqlite busy-timeout:
;; https://github.com/ato/clojars-web/issues/105
;; http://sqlite.org/c3ref/busy_timeout.html
;; https://bitbucket.org/xerial/sqlite-jdbc/issue/27
(defonce _
  (alter-var-root #'clojure.java.jdbc.internal/prepare-statement*
                  (fn [prepare]
                    (fn timeout-prepare [& args]
                      (let [stmt (apply prepare args)]
                        (doto stmt
                          ;; Note that while .getQueryTimeout returns
                          ;; milliseconds, .setQueryTimeout takes seconds!
                          (.setQueryTimeout 30)))))))


(defn find-user [username]
  (first (select users (where {:user username}))))

(defn find-user-by-user-or-email [username-or-email]
  (first (select users (where (or {:user username-or-email}
                                  {:email username-or-email})))))

(defn find-user-by-password-reset-code [reset-code]
  (when-let [user (and (seq reset-code)
                       (first (select users
                                (where {:password_reset_code reset-code}))))]
    (when (> (:password_reset_code_created_at user)
             (-> 1 time/days time/ago time.coerce/to-long))
      user)))

(defn find-groupnames [username]
  (map :name (select groups (fields :name) (where {:user username}))))

(defn group-membernames [groupname]
  (map :user (select groups (fields :user) (where {:name groupname}))))

(defn group-keys [groupname]
  (map :pgp_key (select users (fields :pgp_key)
                        (join groups (= :users.user :groups.user))
                        (where {:groups.name groupname}))))

(defn jars-by-username [username]
  (exec-raw [(str
              "select j.* "
              "from jars j "
              "join "
              "(select group_name, jar_name, max(created) as created "
              "from jars "
              "group by group_name, jar_name) l "
              "on j.group_name = l.group_name "
              "and j.jar_name = l.jar_name "
              "and j.created = l.created "
              "where j.user = ?"
              "order by j.group_name asc, j.jar_name asc")
             [username]]
            :results))

(defn jars-by-groupname [groupname]
    (exec-raw [(str
              "select j.* "
              "from jars j "
              "join "
              "(select jar_name, max(created) as created "
              "from jars "
              "group by group_name, jar_name) l "
              "on j.jar_name = l.jar_name "
              "and j.created = l.created "
              "where j.group_name = ? "
              "order by j.group_name asc, j.jar_name asc")
             [groupname]]
              :results))

(defn recent-versions
  ([groupname jarname]
     (select jars
             (modifier "distinct")
             (fields :version)
             (where {:group_name groupname
                     :jar_name jarname})
             (order :created :desc)))
  ([groupname jarname num]
     (select jars
             (modifier "distinct")
             (fields :version)
             (where {:group_name groupname
                     :jar_name jarname})
             (order :created :desc)
             (limit num))))

(defn count-versions [groupname jarname]
  (-> (exec-raw [(str "select count(distinct version) count from jars"
                      " where group_name = ? and jar_name = ?")
                 [groupname jarname]] :results)
      first
      :count))

(defn recent-jars []
  (exec-raw (str
             "select j.* "
             "from jars j "
             "join "
             "(select group_name, jar_name, max(created) as created "
             "from jars "
             "group by group_name, jar_name) l "
             "on j.group_name = l.group_name "
             "and j.jar_name = l.jar_name "
             "and j.created = l.created "
             "order by l.created desc "
             "limit 6")
            :results))

(defn jar-exists [groupname jarname]
  (-> (exec-raw
        [(str "select exists(select 1 from jars where group_name = ? and jar_name = ?)")
          [groupname jarname]] :results)
  first vals first (= 1)))

(defn find-jar
  ([groupname jarname]
     (or (first (select jars
                        (where (and {:group_name groupname
                                     :jar_name jarname}
                                    (raw "version not like '%-SNAPSHOT'")))
                        (order :created :desc)
                        (limit 1)))
         (first (select jars
                        (where (and {:group_name groupname
                                     :jar_name jarname
                                     :version [like "%-SNAPSHOT"]}))
                        (order :created :desc)
                        (limit 1)))))
  ([groupname jarname version]
     (first (select jars
                    (where (and {:group_name groupname
                                 :jar_name jarname
                                 :version version}))
                    (order :created :desc)
                    (limit 1)))))

(defn all-projects [offset-num limit-num]
  (select jars
    (modifier "distinct")
    (fields :group_name :jar_name)
    (order :group_name :asc)
    (order :jar_name :asc)
    (limit limit-num)
    (offset offset-num)))

(defn count-all-projects []
  (-> (exec-raw
        "select count(*) count from (select distinct group_name, jar_name from jars order by group_name, jar_name)"
        :results)
      first
      :count))

(defn count-projects-before [s]
  (-> (exec-raw
       [(str "select count(*) count from"
              " (select distinct group_name, jar_name from jars"
              "  order by group_name, jar_name)"
              " where group_name || '/' || jar_name < ?")
        [s]] :results)
      first
      :count))

(defn browse-projects [current-page per-page]
  (vec
    (map
      #(find-jar (:group_name %) (:jar_name %))
      (all-projects
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

(defn add-user [email username password pgp-key]
  (let [record {:email email, :user username, :password (bcrypt password),
                :pgp_key pgp-key}
        group (str "org.clojars." username)]
    (serialize-task :add-user
      (insert users (values (assoc record
                              :created (get-time)
                              ;;TODO: remove salt and ssh_key field
                              :ssh_key ""
                              :salt "")))
      (insert groups (values {:name group :user username})))
    record))

(defn update-user [account email username password pgp-key]
  (let [fields {:email email
                :user username
                :pgp_key pgp-key}
        fields (if (empty? password)
                 fields
                 (assoc fields :password (bcrypt password)))]
    (serialize-task :update-user
      (update users
        (set-fields (assoc fields :salt "" :ssh_key ""))
        (where {:user account})))
    fields))

(defn update-user-password [reset-code password]
  (assert (not (str/blank? reset-code)))
  (let [fields {:password (bcrypt password)
                :password_reset_code nil
                :password_reset_code_created_at nil}]
    (serialize-task :update-user-password
      (update users
        (set-fields (assoc fields :salt ""))
        (where {:password_reset_code reset-code})))))

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

(defn set-password-reset-code! [username-or-email]
  (let [reset-code (hexadecimalize (generate-secure-token 20))]
    (serialize-task :set-password-reset-code
      (update users
        (set-fields {:password_reset_code reset-code
                     :password_reset_code_created_at (get-time)})
        (where (or {:user username-or-email}
                   {:email username-or-email}))))
    reset-code))

(defn add-member [group-id username added-by]
  (serialize-task :add-member
    (insert groups
      (values {:name group-id
               :user username
               :added_by added-by}))))

(defn check-and-add-group [account groupname]
  (when-not (re-matches #"^[a-z0-9-_.]+$" groupname)
    (throw (Exception. (str "Group names must consist of lowercase "
                            "letters, numbers, hyphens, underscores "
                            "and full-stops."))))
  (let [members (group-membernames groupname)]
    (if (empty? members)
      (if (reserved-names groupname)
        (throw (Exception. (str "The group name "
                                groupname
                                " is already taken.")))
        (add-member groupname account "clojars"))
      (when-not (some #{account} members)
        (throw (Exception. (str "You don't have access to the "
                                groupname " group.")))))))

(defn add-jar [account {:keys [group name version
                               description homepage authors]}]
  (check-and-add-group account group)
  (serialize-task :add-jar
    (insert jars
      (values {:group_name group
               :jar_name   name
               :version    version
               :user       account
               :created    (get-time)
               :description description
               :homepage   homepage
               :authors    (str/join ", " (map #(.replace % "," "")
                                            authors))}))))

(defn delete-jars [group-id & [jar-id version]]
  (let [column-mappings {:group-id :group_name
                         :jar-id   :jar_name}
        coords {:group_name group-id}
        coords (if jar-id
                 (assoc coords :jar_name jar-id)
                 coords)
        coords (if version
                 (assoc coords :version version)
                 coords)]
    (serialize-task :delete-jars
      (delete jars (where coords)))))

;; does not delete jars in the group. should it?
(defn delete-groups [group-id]
  (serialize-task :delete-groups
    (delete groups
      (where {:name group-id}))))
