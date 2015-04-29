(ns clojars.db
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojars.event :as ev]
            [clojars.config :refer [config]]
            [korma.db :refer [defdb transaction rollback]]
            [korma.core :refer [defentity select group fields order join
                                modifier exec-raw where limit values with
                                has-many raw insert update delete set-fields
                                offset]]
            [cemerick.friend.credentials :as creds])
  (:import java.security.MessageDigest
           java.util.Date
           java.io.File
           java.util.concurrent.Executors))

(def ^{:private true} ssh-options
  "no-agent-forwarding,no-port-forwarding,no-pty,no-X11-forwarding")

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

(defn split-keys [s]
  (map str/trim (str/split s #"\s*\n\s*")))

(defn write-key-file [path]
  (locking (:key-file config)
    (let [new-file (File. (str path ".new"))]
      (with-open [f (io/writer new-file)]
        (doseq [{:keys [user ssh_key]} (select users (fields :user :ssh_key))
                key (remove str/blank? (split-keys ssh_key))]
          (.write f (str "command=\"ng --nailgun-port 8700 clojars.scp " user
                         "\"," ssh-options " " key "\n"))))
      (.renameTo new-file (File. path)))))

(defn find-user [username]
  (first (select users (where {:user username}))))

(defn find-user-by-user-or-email [username-or-email]
  (first (select users (where (or {:user username-or-email}
                                  {:email username-or-email})))))

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

(defn add-user [email username password ssh-key pgp-key]
  (let [record {:email email, :user username, :password (bcrypt password)
                :ssh_key ssh-key, :pgp_key pgp-key}
        group (str "org.clojars." username)]
    (serialize-task :add-user
      (insert users (values (assoc record
                              :created (get-time)
                              ;;TODO: remove salt field
                              :salt "")))
      (insert groups (values {:name group :user username})))
    (ev/record :user (clojure.set/rename-keys record {:user :username
                                                      :ssh_key :ssh-key
                                                      :pgp_key :pgp-key}))
    (ev/record :membership {:group-id group :username username :added-by nil})
    (write-key-file (:key-file config))))

(defn update-user [account email username password ssh-key pgp-key]
  (let [fields {:email email
                :user username
                :ssh_key ssh-key
                :pgp_key pgp-key}
        fields (if (empty? password)
                 fields
                 (assoc fields :password (bcrypt password)))]
    (serialize-task :update-user
      (update users
        (set-fields (assoc fields :salt ""))
        (where {:user account})))
    (ev/record :user (clojure.set/rename-keys fields {:user :username
                                                      :ssh_key :ssh-key
                                                      :pgp_key :pgp-key})))
  (write-key-file (:key-file config)))

(defn add-member [group-id username added-by]
  (serialize-task :add-member
    (insert groups
      (values {:name group-id
               :user username
               :added_by added-by})))
  (ev/record :membership {:group-id group-id :username username
                          :added-by added-by}))

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
      (delete jars (where coords))))
  ;; TODO: record an event?
  )

;; does not delete jars in the group. should it?
(defn delete-groups [group-id]
  (serialize-task :delete-groups
    (delete groups
      (where {:name group-id})))
  ;; TODO: record an event?
  )
