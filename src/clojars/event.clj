(ns clojars.event
  (:refer-clojure :exclude [load])
  (:require [clojure.java.io :as io]
            [clojure.java.jdbc :as sql]
            [clojure.set :as set]
            [clojars.config :refer [config]]
            [clojars.search :as search]
            [clucy.core :as clucy])
  (:import (org.apache.commons.codec.digest DigestUtils)))

(defn event-log-file [type]
  (io/file (config :event-dir) (str (name type) ".clj")))

(defn record [type event]
  (let [filename (event-log-file type)
        content (prn-str (assoc event :at (:at event (java.util.Date.))))]
    (locking #'record
      (spit filename content :append true))))

(defn record-deploy [{:keys [group-id artifact-id version]} deployed-by file]
  (when (.endsWith (str file) ".pom")
    (with-open [index (clucy/disk-index (config :index-path))]
      (search/index-pom index file)))
  (record :deploy {:group-id group-id
                   :artifact-id artifact-id
                   :version version
                   :deployed-by deployed-by
                   :filename (str file)
                   :sha1 (DigestUtils/shaHex (io/input-stream file))}))

(defonce users (atom {}))

(defonce memberships (atom {}))

(defonce deploys (atom {}))

(defn add-user [users {:keys [username email] :as user}]
  (-> users
      (update-in [username] merge user)
      (update-in [email] merge user)))

(defn add-member [memberships {:keys [group-id username]}]
  (update-in memberships [group-id] (fnil conj #{}) username))

(defn add-user-membership [users {:keys [group-id username]}]
  (update-in users [username :groups] (fnil conj #{}) group-id))

(defn add-deploy [deploys {:keys [group-id artifact-id] :as deploy}]
  (assoc deploys (str group-id "/" artifact-id) deploy))

(defn load-users [file]
  (with-open [r (io/reader file)]
    (swap! users #(reduce add-user % (map read-string (line-seq r))))))

(defn load-memberships [file]
  (with-open [r (io/reader file)]
    (swap! memberships #(reduce add-member % (map read-string (line-seq r))))
    (swap! users #(reduce add-user-membership %
                          (map read-string (line-seq r))))))

(defn load-deploys [file]
  (with-open [r (io/reader file)]
    (swap! users #(reduce add-deploy % (map read-string (line-seq r))))))

(defn load []
  (println "Loading users, memberships, and deploys from event logs...")
  (time (load-users (event-log-file :user)))
  (time (load-memberships (event-log-file :membership)))
  (time (load-deploys (event-log-file :deploy))))

(defn seed
  "Seed event log with initial values from SQLite DB"
  [[& sanitize?]]
  (sql/with-connection (config :db)
    (sql/with-query-results groups ["SELECT * FROM groups"]
      (doseq [{:keys [name user]} groups]
        (record :membership {:group-id name :username user :added-by nil})))
    (sql/with-query-results users ["SELECT * FROM users"]
      (doseq [{:keys [user password email created ssh_key pgp_key]} users]
        (record :user {:username user :email email
                       :password (or sanitize? password)
                       :ssh-key ssh_key :pgp-key pgp_key
                       :at created :from "sqlite"})))
    (sql/with-query-results jars ["SELECT * FROM jars"]
      (doseq [{:keys [jar_name group_name version user]} jars]
        (try
          (record-deploy {:group-id group_name :artifact-id  jar_name
                          :version version} user
                          (io/file (config :repo) group_name jar_name version
                                   (format "%s-%s.pom" jar_name version)))
          (catch Exception e
            (println (.getMessage e))))))))