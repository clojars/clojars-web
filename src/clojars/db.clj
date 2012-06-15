(ns clojars.db
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojars.config :refer [config]]
            [korma.db :refer [defdb transaction rollback]]
            [korma.core :refer [defentity select group fields order
                                modifier exec-raw where limit values
                                raw insert update set-fields]])
  (:import java.security.MessageDigest
           java.util.Date
           java.io.File
           org.mindrot.jbcrypt.BCrypt))

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
  (apply str (repeatedly n #(rand-nth constituent-chars))))

(defn ^:dynamic get-time []
  (Date.))

(defn bcrypt [s]
  (BCrypt/hashpw s (BCrypt/gensalt (:bcrypt-work-factor config))))

(defdb mydb (:db config))
(defentity users)
(defentity groups)
(defentity jars)

(defn write-key-file [path]
  (locking (:key-file config)
    (let [new-file (File. (str path ".new"))]
      (with-open [f (io/writer new-file)]
        (doseq [{:keys [user ssh_key]} (select users (fields :user :ssh_key))]
          (.write f (str "command=\"ng --nailgun-port 8700 clojars.scp " user
                         "\"," ssh-options " "
                         (.replaceAll (.trim ssh_key)
                                      "[\n\r\0]" "")
                         "\n"))))
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

(defn jars-by-username [username]
  (select jars
          (where {:user username})
          (group :group_name :jar_name)))

(defn jars-by-groupname [groupname]
  (select jars
          (where {:group_name groupname})
          (group :jar_name)))

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
  (select jars
          (group :group_name :jar_name)
          (order :created :desc)
          (limit 5)))

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

(defn add-user [email username password ssh-key]
  (insert users
          (values {:email email
                   :user username
                   :password (bcrypt password)
                   :ssh_key ssh-key
                   :created (get-time)
                   ;;TODO: remove salt field
                   :salt ""}))
  (insert groups
          (values {:name (str "org.clojars." username)
                   :user username}))
  (write-key-file (:key-file config)))

(defn update-user [account email username password ssh-key]
  (update users
          (set-fields {:email email
                       :user username
                       :salt ""
                       :password (bcrypt password)
                       :ssh_key ssh-key})
          (where {:user account}))
  (write-key-file (:key-file config)))

(defn add-member [groupname username]
  (insert groups
          (values {:name groupname
                   :user username})))

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
        (add-member groupname account))
      (when-not (some #{account} members)
        (throw (Exception. (str "You don't have access to the "
                                groupname " group.")))))))

(defn- add-jar-helper [account {:keys [group name version
                                       description homepage authors]}]
  (check-and-add-group account group)
  (insert jars
          (values {:group_name group
                   :jar_name   name
                   :version    version
                   :user       account
                   :created    (get-time)
                   :description description
                   :homepage   homepage
                   :authors    (str/join ", " (map #(.replace % "," "")
                                                   authors))})))

(defn update-jar [account {:keys [group name version
                                  description homepage authors]}]
  (update jars
          (set-fields {:user       account
                       :created    (get-time)
                       :description description
                       :homepage   homepage
                       :authors    (str/join ", " (map #(.replace % "," "")
                                                       authors))})
          (where {:group_name group
                  :jar_name   name
                  :version    version})))

(defn add-jar [account jarmap & [check-only]]
  (when-not (re-matches #"^[a-zA-Z0-9-_.]+$" (:name jarmap))
    (throw (Exception. (str "Jar names must consist solely of "
                            "letters, numbers, hyphens and underscores."))))
  (transaction
   (if check-only
     (do (rollback)
         (add-jar-helper account jarmap))
     (add-jar-helper account jarmap))))

(defn quote-hyphenated
  "Wraps hyphenated-words in double quotes."
  [s]
  (str/replace s #"\w+(-\w+)+" "\"$0\""))

(defn search-jars [query & [offset]]
  ;; TODO make search less stupid, figure out some relevance ranking
  ;; scheme, do stopwords etc.
  (let [r (exec-raw [(str "select jar_name, group_name from search where "
                          "content match ? "
                          "order by rowid desc "
                          "limit 100 "
                          "offset ?")
                     [(quote-hyphenated query)
                      (or offset 0)]]
                    :results)]
    ;; TODO: do something less stupidly slow
    (vec (map #(find-jar (:group_name %) (:jar_name %)) r))))
