(ns clojars.db.migrate
  (:require [clojure.java.jdbc :as sql]
            [clojure.java.io :as io]
            [clojars.config :refer [config]])
  (:import (java.sql Timestamp)))

(defn initial-schema [trans]
  (doseq [cmd (.split (slurp "clojars.sql") ";\n\n")]
    ;; needs to succeed even if tables exist since this migration
    ;; hasn't been recorded in extant DBs before migrations were introduced
    (try (sql/db-do-commands trans cmd)
         (catch java.sql.BatchUpdateException _))))

(defn add-promoted-field [trans]
  (sql/db-do-commands trans "ALTER TABLE jars ADD COLUMN promoted_at DATE"))

(defn add-jars-index [trans]
  ;; speed up the front page and selects for jars by name
  (sql/db-do-commands trans
                      (str "CREATE INDEX IF NOT EXISTS jars_idx0 "
                        "ON jars (group_name, jar_name, created DESC)")))

(defn add-pgp-key [trans]
  (sql/db-do-commands trans "ALTER TABLE users ADD COLUMN pgp_key TEXT"))

(defn add-added-by [trans]
  (sql/db-do-commands trans "ALTER TABLE groups ADD COLUMN added_by TEXT"))

(defn add-password-reset-code [trans]
  (sql/db-do-commands trans "ALTER TABLE users ADD COLUMN password_reset_code TEXT"))

(defn add-password-reset-code-created-at [trans]
  (sql/db-do-commands trans "ALTER TABLE users ADD COLUMN password_reset_code_created_at DATE"))

;; migrations mechanics

(defn run-and-record [migration trans]
  (println "Running migration:" (:name (meta migration)))
  (migration trans)
  (sql/insert! trans
               "migrations"
               [:name :created_at]
               [(str (:name (meta migration)))
                (Timestamp. (System/currentTimeMillis))]))

(defn- ensure-db-directory-exists [db]
  (when-not (.exists (io/file db))
    (.mkdirs (.getParentFile (io/file db)))))

(def migrations
  [#'initial-schema
   #'add-promoted-field
   #'add-jars-index
   #'add-pgp-key
   #'add-added-by
   #'add-password-reset-code
   #'add-password-reset-code-created-at])

(defn migrate [db]
  (try (sql/db-do-commands db
                           (sql/create-table-ddl "migrations"
                                                 [:name :varchar "NOT NULL"]
                                                 [:created_at :timestamp
                                                  "NOT NULL"  "DEFAULT CURRENT_TIMESTAMP"])) 
       (catch Exception _))
  (sql/with-db-transaction [trans db]
    (let [has-run? (sql/query trans ["SELECT name FROM migrations"]
                              :row-fn :name
                              :result-set-fn set)]
      (doseq [m migrations
              :when (not (has-run? (str (:name (meta m)))))]
        (run-and-record m trans)))))


(defn -main []
  (let [db (:db config)]
    (ensure-db-directory-exists (:subname db))
    (migrate db)))
