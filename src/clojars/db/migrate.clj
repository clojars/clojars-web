(ns clojars.db.migrate
  (:require [clojure.java.io :as io]
            [clojure.java.jdbc :as sql]
            [clojure.string :as str])
  (:import (java.io File)
           (java.sql Timestamp)))

(defn initial-schema [trans]
  (doseq [cmd (map str/trim
                   (-> (str "queries" (File/separator) "clojars.sql")
                       io/resource
                       slurp
                       (str/split #";\s*")))
          :when (not (.isEmpty cmd))
          :when (not (re-find #"^\s*--" cmd))]
    (sql/db-do-commands trans cmd)))

;; migrations mechanics

(defn run-and-record [migration trans]
  (println "Running migration:" (:name (meta migration)))
  (migration trans)
  (sql/insert! trans
               "migrations"
               [:name :created_at]
               [(str (:name (meta migration)))
                (Timestamp. (System/currentTimeMillis))]))

(defn- add-deploy-tokens-table
  [trans]
  (sql/db-do-commands trans
                      (str "create table deploy_tokens "
                           "(id serial not null primary key,"
                           " user_id integer not null references users(id) on delete cascade,"
                           " name text not null,"
                           " token text unique not null,"
                           " created timestamp not null default current_timestamp,"
                           " updated timestamp not null default current_timestamp,"
                           " disabled boolean not null default false)")))

(defn- add-last-used-to-deploy-tokens-table
  [trans]
  (sql/db-do-commands trans
                      (str "alter table deploy_tokens "
                           "add last_used timestamp default null")))

(defn- add-group-and-jar-to-deploy-tokens-table
  [trans]
  (sql/db-do-commands trans
                      (str "alter table deploy_tokens "
                           "add group_name text default null,"
                           "add jar_name text default null")))

(defn- add-mfa-fields-to-users-table
  [trans]
  (sql/db-do-commands trans
                      (str "alter table users "
                           "add otp_secret_key text default null,"
                           "add otp_recovery_code text default null,"
                           "add otp_active boolean default false")))

(def migrations
  [#'initial-schema
   #'add-deploy-tokens-table
   #'add-last-used-to-deploy-tokens-table
   #'add-group-and-jar-to-deploy-tokens-table
   #'add-mfa-fields-to-users-table])

(defn migrate [db]
  (sql/db-do-commands db
                      (str "create table if not exists migrations "
                           "(name varchar not null, "
                           "created_at timestamp not null default current_timestamp)"))
  (sql/with-db-transaction [trans db]
    (let [has-run? (sql/query trans ["SELECT name FROM migrations"]
                              :row-fn :name
                              :result-set-fn set)]
      (doseq [m migrations
              :when (not (has-run? (str (:name (meta m)))))]
        (run-and-record m trans)))))

