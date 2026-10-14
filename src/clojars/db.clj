(ns clojars.db
  (:require
   [cemerick.friend.credentials :as creds]
   [clojars.config :refer [config]]
   [clojure.string :as str]
   [honey.sql :as hsql]
   [next.jdbc :as jdbc]
   [next.jdbc.prepare :as jdbc.prepare]
   [next.jdbc.result-set :as jdbc.result-set]
   [next.jdbc.sql :as sql])
  (:import
   (java.security
    SecureRandom)
   (java.sql
    Timestamp)))

(set! *warn-on-reflection* true)

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
    "internal"
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

;; From https://cljdoc.org/d/com.github.seancorfield/next.jdbc/1.3.894/doc/migration-from-clojure-java-jdbc
(defn do-commands
  [connectable commands]
  (if (instance? java.sql.Connection connectable)
    (with-open [stmt (jdbc.prepare/statement connectable)]
      (run! #(.addBatch stmt %) commands)
      (into [] (.executeBatch stmt)))
    (with-open [conn (jdbc/get-connection connectable)]
      (do-commands conn commands))))

(defn get-time ^Timestamp []
  (Timestamp. (System/currentTimeMillis)))

(defn bcrypt [s]
  (creds/hash-bcrypt s :work-factor (:bcrypt-work-factor (config))))

(defn q
  ([db query-data]
   (q db query-data nil))
  ([db query-data opts]
   (sql/query db (hsql/format query-data {:quoted true})
              (assoc opts
                     :builder-fn jdbc.result-set/as-unqualified-lower-maps))))

(defn execute!
  [db query-data]
  (jdbc/execute! db (hsql/format query-data {:quoted true})))

(def user-column
  "user is a reserved word, so we have to quote it."
  "\"user\"")

;; Reference:
;; https://github.com/xavi/noir-auth-app/blob/master/src/noir_auth_app/models/user.clj
;; https://github.com/weavejester/crypto-random/blob/master/src/crypto/random.clj
;; https://jira.atlassian.com/browse/CWD-1897?focusedCommentId=196759&page=com.atlassian.jira.plugin.system.issuetabpanels:comment-tabpanel#comment-196759
(defn- generate-secure-token [size]
  (let [seed (byte-array size)]
    ;; http://docs.oracle.com/javase/6/docs/api/java/security/SecureRandom.html
    (.nextBytes (SecureRandom/getInstance "SHA1PRNG") seed)
    seed))

(defn- hexadecimalize [byte-array]
  ;; converts byte array to hex string
  ;; http://stackoverflow.com/a/8015558/974795
  (str/lower-case (apply str (map #(format "%02X" %) byte-array))))

(def generate-secure-hex-token (comp hexadecimalize generate-secure-token))
