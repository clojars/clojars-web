(ns clojars.db
  (:use [clojars.config :only [config]]
        korma.db
        korma.core)
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

(defn bcrypt [s]
  (BCrypt/hashpw s (BCrypt/gensalt (:bcrypt-work-factor config))))

;; ಠ_ಠ
(defn sha1 [& s]
  (when-let [s (seq s)]
    (let [md (MessageDigest/getInstance "SHA")]
      (.update md (.getBytes (apply str s)))
      (format "%040x" (java.math.BigInteger. 1 (.digest md))))))

(defdb mydb (:db config))
(defentity users)
(defentity groups)
(defentity jars)

(defn bcrypt [s]
  (BCrypt/hashpw s (BCrypt/gensalt (:bcrypt-work-factor config))))

(defn find-user [username]
  (first (select users (where {:user username}))))

(defn find-user-by-user-or-email [user-or-email]
  (first (select users (where (or {:user user-or-email}
                                  {:email user-or-email})))))

(defn find-groups [username]
  (map :name (select groups (fields :name) (where {:user username}))))

(defn group-members [group]
  (map :user (select groups (fields :user) (where {:name group}))))

(defn authed? [plaintext user]
  (or (try (BCrypt/checkpw plaintext (:password user))
           (catch java.lang.IllegalArgumentException _))
      (= (:password user) (sha1 (:salt user) plaintext))))

(defn auth-user [user plaintext]
  (first (filter (partial authed? plaintext)
                 (select users (where (or {:user user}
                                          {:email user}))))))

(defn jars-by-user [user]
  (select jars
          (where {:user user})
          (group :group_name :jar_name)))

(defn jars-by-group [group-name]
  (select jars
          (where {:group_name group-name})
          (group :jar_name)))

(defn recent-versions
  ([group jarname]
     (select jars
             (modifier "distinct")
             (fields :version)
             (where {:group_name group
                     :jar_name jarname})
             (order :created :desc)))
  ([group jarname num]
     (select jars
             (modifier "distinct")
             (fields :version)
             (where {:group_name group
                     :jar_name jarname})
             (order :created :desc)
             (limit num))))

(defn count-versions [group jarname]
  (-> (exec-raw [(str "select count(distinct version) count from jars"
                      " where group_name = ? and jar_name = ?")
                 [group jarname]] :results)
      first
      :count))

(defn recent-jars []
  (select jars
          (group :group_name :jar_name)
          (order :created :desc)
          (limit 5)))

(defn find-jar
  ([group jarname]
     (or (first (select jars
                        (where (and {:group_name group
                                     :jar_name jarname}
                                    (raw "version not like '%-SNAPSHOT'")))
                        (order :created :desc)
                        (limit 1)))
         (first (select jars
                        (where (and {:group_name group
                                     :jar_name jarname
                                     :version [like "%-SNAPSHOT"]}))
                        (order :created :desc)
                        (limit 1)))))
  ([group jarname version]
     (first (select jars
                    (where (and {:group_name group
                                 :jar_name jarname
                                 :version version}))
                    (order :created :desc)
                    (limit 1)))))

(defn add-user [email user password ssh-key]
  (insert users
          (values {:email email
                   :user user
                   :password (bcrypt password)
                   :ssh_key ssh-key
                   :created (get-time)
                   ;;TODO: remove salt field
                   :salt ""}))
  (insert groups
          (values {:name (str "org.clojars." user)
                   :user user}))
  (write-key-file (:key-file config)))

(defn update-user [account email user password ssh-key]
  (update users
          (set-fields {:email email
                       :user user
                       :salt ""
                       :password (bcrypt password)
                       :ssh_key ssh-key})
          (where {:user account}))
  (write-key-file (:key-file config)))

(defn add-member [group user]
  (insert groups
          (values {:name group
                   :user user})))

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
  (insert jars
          (values {:group_name (:group jarmap)
                   :jar_name   (:name jarmap)
                   :version    (:version jarmap)
                   :user       account
                   :created    (get-time)
                   :description (:description jarmap)
                   :homepage   (:homepage jarmap)
                   :authors    (str/join ", " (map #(.replace % "," "")
                                                   (:authors jarmap)))})))

(defn- using-savepoints-rollback [f]
  ;;Required for tests as clojure.java.jdbc
  ;;doesn't handle rolling back nested transactions
  ;;Also sqlite driver doesn't support .setSavepoint
  (exec-raw ["SAVEPOINT s"])
  (try
    (f)
    (finally
     (exec-raw ["ROLLBACK TO s"]))))

(defn add-jar [account jarmap & [check-only]]
  (when-not (re-matches #"^[a-z0-9-_.]+$" (:name jarmap))
    (throw (Exception. (str "Jar names must consist solely of lowercase "
                            "letters, numbers, hyphens and underscores."))))
  ;; TODO remove when fixed in korma
  ;; Work around for korma generating a different
  ;; connection for nested transactions
  (if (sql/find-connection)
    (sql/transaction
     (if check-only
       (using-savepoints-rollback
        (partial add-jar-helper account jarmap))
       (add-jar-helper account jarmap)))
    (transaction
     (if check-only
       (using-savepoints-rollback
        (partial add-jar-helper account jarmap))
       (add-jar-helper account jarmap)))))

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
