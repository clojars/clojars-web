(ns clojars.db
  (:use [clojars.config :only [config]])
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.java.jdbc :as sql])
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

(defn write-key-file [path]
  (locking (:key-file config)
   (let [new-file (File. (str path ".new"))]
     (sql/with-query-results rs ["select user, ssh_key from users"]
       (with-open [f (io/writer new-file)]
         (doseq [{:keys [user ssh_key]} rs]
           (.write f (str "command=\"ng --nailgun-port 8700 clojars.scp " user
                            "\"," ssh-options " "
                            (.replaceAll (.trim ssh_key)
                                         "[\n\r\0]" "")
                            "\n")))))
     (.renameTo new-file (File. path)))))

(defmacro with-db
  [& body]
  ;;TODO does connection sharing break something when deployed?
  `(if (sql/find-connection)
     (do ~@body)
     (sql/with-connection (:db config)
       ~@body)))

(defn bcrypt [s]
  (BCrypt/hashpw s (BCrypt/gensalt (:bcrypt-work-factor config))))

;; ಠ_ಠ
(defn sha1 [& s]
  (when-let [s (seq s)]
    (let [md (MessageDigest/getInstance "SHA")]
      (.update md (.getBytes (apply str s)))
      (format "%040x" (java.math.BigInteger. 1 (.digest md))))))

(defn find-user [username]
  (sql/with-query-results rs ["select * from users where user = ?" username]
    (first rs)))

(defn find-user-by-user-or-email [user-or-email]
  (sql/with-query-results rs ["select * from users where user = ? or email = ?" user-or-email user-or-email]
    (first rs)))

(defn find-groups [username]
  (sql/with-query-results rs ["select * from groups where user = ?" username]
    (doall (map :name rs))))

(defn group-members [group]
  (sql/with-query-results rs ["select * from groups where name like ?" group]
    (doall (map :user rs))))

(defn authed? [plaintext user]
  (or (try (BCrypt/checkpw plaintext (:password user))
           (catch java.lang.IllegalArgumentException _))
      (= (:password user) (sha1 (:salt user) plaintext))))

(defn auth-user [user plaintext]
  (sql/with-query-results rs
    ["select * from users where (user = ? or email = ?)" user user]
    (first (filter (partial authed? plaintext) rs))))

(defn jars-by-user [user]
  (sql/with-query-results rs [(str "select * from jars where user = ? "
                               "group by group_name, jar_name") user]
    (vec rs)))

(defn jars-by-group [group]
  (sql/with-query-results rs [(str "select * from jars where "
                                   "group_name = ? group by jar_name")
                              group]
    (vec rs)))

(defn recent-versions
  ([group jarname]
     (sql/with-query-results rs
       [(str "select distinct version from jars where group_name = ? "
             "and jar_name = ? order by created desc ") group jarname]
       (vec rs)))
    ([group jarname num]
     (sql/with-query-results rs
       [(str "select distinct version from jars where group_name = ? "
             "and jar_name = ? order by created desc "
             "limit ?") group jarname num]
       (vec rs))))

(defn count-versions [group jarname]
  (sql/with-query-results rs
    [(str "select count(distinct version) from jars where group_name = ? "
          "and jar_name = ?") group jarname]
    ((first rs) (keyword "count(distinct version)"))))

(defn recent-jars []
  (sql/with-query-results rs
    [(str "select * from jars group by group_name, jar_name "
          "order by created desc limit 5")]
    (vec rs)))

(defn find-jar
  ([group jarname]
     (or (sql/with-query-results rs
           [(str "select * from jars where group_name = ? and "
                 "jar_name = ? and version not like '%-SNAPSHOT'"
                 " order by created desc limit 1")
            group jarname]
           (first rs))
         (sql/with-query-results rs
           [(str "select * from jars where group_name = ? and "
                 "jar_name = ? and version like '%-SNAPSHOT'"
                 " order by created desc limit 1")
            group jarname]
           (first rs))))
  ([group jarname version]
     (sql/with-query-results rs
       [(str "select * from jars where group_name = ? and "
             "jar_name = ? and version = ?"
             " order by created desc limit 1")
        group jarname version]
       (first rs))))

(defn add-user [email user password ssh-key]
  (sql/insert-values :users
                     ;; TODO: remove salt field
                     [:email :user :password :salt :ssh_key :created]
                     [email user (bcrypt password) "" ssh-key (get-time)])
  (sql/insert-values :groups
                     [:name :user]
                     [(str "org.clojars." user) user])
  (write-key-file (:key-file config)))

(defn update-user [account email user password ssh-key]
  (sql/update-values :users ["user=?" account]
                     {:email email
                      :user user
                      :salt ""
                      :password (bcrypt password)
                      :ssh_key ssh-key})
  (write-key-file (:key-file config)))

(defn add-member [group user]
  (sql/insert-records :groups {:name group :user user}))

(defn check-and-add-group [account group jar]
  (when-not (re-matches #"^[a-z0-9-_.]+$" group)
    (throw (Exception. (str "Group names must consist of lowercase "
                            "letters, numbers, hyphens, underscores "
                            "and full-stops."))))
  (let [members (group-members group)]
    (if (empty? members)
      (if (reserved-names group)
        (throw (Exception. (str "The group name " group " is already taken.")))
        (add-member group account))
      (when-not (some #{account} members)
        (throw (Exception. (str "You don't have access to the "
                                group " group.")))))))

(defn- add-jar-helper [account jarmap]
  (check-and-add-group account (:group jarmap) (:name jarmap))
  (sql/insert-records :jars
                      {:group_name (:group jarmap)
                       :jar_name   (:name jarmap)
                       :version    (:version jarmap)
                       :user       account
                       :created    (get-time)
                       :description (:description jarmap)
                       :homepage   (:homepage jarmap)
                       :authors    (str/join ", " (map #(.replace % "," "")
                                                       (:authors jarmap)))}))

(defn- using-savepoints-rollback [f]
  ;;Required for tests as clojure.java.jdbc
  ;;doesn't handle rolling back nested transactions
  ;;Also sqlite driver doesn't support .setSavepoint
  (sql/do-commands "SAVEPOINT s")
  (try
    (f)
    (finally
     (sql/do-commands "ROLLBACK TO s"))))

(defn add-jar [account jarmap & [check-only]]
  (when-not (re-matches #"^[a-z0-9-_.]+$" (:name jarmap))
    (throw (Exception. (str "Jar names must consist solely of lowercase "
                            "letters, numbers, hyphens and underscores."))))
  (sql/transaction (if check-only
                     (using-savepoints-rollback (partial add-jar-helper
                                                         account jarmap))
                     (add-jar-helper account jarmap))))

(defn quote-hyphenated
  "Wraps hyphenated-words in double quotes."
  [s]
  (str/replace s #"\w+(-\w+)+" "\"$0\""))

(defn search-jars [query & [offset]]
  ;; TODO make search less stupid, figure out some relevance ranking
  ;; scheme, do stopwords etc.
  (sql/with-query-results rs
      [(str "select jar_name, group_name from search where "
            "content match ? "
            "order by rowid desc "
            "limit 100 "
            "offset ?")
       (quote-hyphenated query)
       (or offset 0)]
    ;; TODO: do something less stupidly slow
    (vec (map #(find-jar (:group_name %) (:jar_name %)) rs))))

(comment
  (with-db
    (add-jar "atotx" {:name "test3" :group "test3" :version "1.0"
                      :description "An dog awesome and non-existent test jar."
                      :homepage "http://clojars.org/"
                      :authors ["Alex Osborne" "a little fish"]}))
  (with-db (find-user "tech3")))
