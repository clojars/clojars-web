(ns clojars.db
  (:use clojure.contrib.sql
        clojure.contrib.duck-streams
        [clojure.contrib.str-utils2 :only [join]])
  (:import java.security.MessageDigest
           java.util.Date
           java.io.File))
;;
;; TODO: should move this to a config file
;;
(def db {:classname "org.sqlite.JDBC"
         :subprotocol "sqlite"
         :subname "/home/clojars/data/db"})

(def key-file "/home/clojars/data/auth_keys")
(def ssh-options "no-agent-forwarding,no-port-forwarding,no-pty,no-X11-forwarding")

(defn write-key-file [path]
  (locking key-file
   (let [new-file (File. (str path ".new"))]
     (with-query-results rs ["select user, ssh_key from users"]
       (with-open [f (writer new-file)]
         (doseq [x rs]
           (.println f (str "command=\"ng --nailgun-port 8700 clojars.scp " (:user x) 
                            "\"," ssh-options " " 
                            (.replaceAll (.trim (:ssh_key  x)) 
                                               "[\n\r\0]" ""))))))
     (.renameTo new-file (File. path)))))

(defn with-db
  [handler]
  (fn [request]
    (with-connection db (handler request))))

(defn sha1 [s]
  (when s
    (let [md (MessageDigest/getInstance "SHA")]
      (.update md (.getBytes s))
      (format "%040x" (java.math.BigInteger. 1 (.digest md))))))

(defn find-user [username]
  (with-query-results rs ["select * from users where user = ?" username]
    (first rs)))

(defn auth-user [user pass]
  (with-query-results rs ["select * from users where (user = ? or
                           email = ?) and password = ?" user 
                           user (sha1 pass)]
    (first rs)))

(defn add-user [email user password ssh-key]
  (insert-values :users
    [:email :user :password :ssh_key :created]
    [email user (sha1 password) ssh-key (Date.)])
  (write-key-file key-file))

(defn update-user [account email user password ssh-key]
  (update-values :users ["user=?" account]
   {:email email :user user :password (sha1 password)
    :ssh_key ssh-key})
  (write-key-file key-file))

(defn insert-value-pairs [table coll])

(defn add-jar [account jarmap]
  (insert-records
   :jars
   {:group_name (namespace (:name jarmap))
    :jar_name   (name (:name jarmap))
    :version    (:version jarmap)
    :user       account
    :created    (Date.)
    :description (:description jarmap)
    :homepage   (:homepage jarmap)
    :authors    (join ", " (.replace "," ""))}))


