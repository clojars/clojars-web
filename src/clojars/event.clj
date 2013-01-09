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
        content (prn-str (assoc event :at (java.util.Date.)))]
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

(defn load
  "Load users and memberships from event logs."
  []
  (load-users (event-log-file :user))
  (load-memberships (event-log-file :membership)))
(defn load-deploys [file]
  (with-open [r (io/reader file)]
    (swap! users #(reduce add-deploy % (map read-string (line-seq r))))))

(defn seed
  "Seed event log with initial values from SQLite DB"
  [[& sanitize?]]
  (sql/with-connection (config :db)
    (sql/with-query-results groups ["SELECT * FROM groups"]
      (doseq [{:keys [name user]} groups]
        (record :membership {:group name :username user :added-by nil})))
    (sql/with-query-results users ["SELECT * FROM users"]
      (doseq [{:keys [user password email created ssh_key pgp_key]} users]
        (record :user {:username user :email email
                       :password (or sanitize? password)
                       :ssh-key ssh_key :pgp-key pgp_key
                       :at created :from "sqlite"})))))