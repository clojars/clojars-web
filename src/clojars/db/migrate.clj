(ns clojars.db.migrate
  (:require [clojure.java.jdbc :as sql]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.sql Timestamp BatchUpdateException)
           (java.io File)))

(defn initial-schema [trans]
  (doseq [cmd (map str/trim
                   (-> (str "queries" (File/separator) "clojars.sql")
                       io/resource
                       slurp
                       (str/split #";\s*")))
          :when (not (.isEmpty cmd))
          :when (not (re-find #"^\s*--" cmd))]
    ;; needs to succeed even if tables exist since this migration
    ;; hasn't been recorded in extant DBs before migrations were introduced
    (try (sql/db-do-commands trans cmd)
         (catch BatchUpdateException e
           (.printStackTrace e)))))

(defn add-promoted-field [trans]
  (sql/db-do-commands trans "ALTER TABLE jars ADD COLUMN promoted_at DATE"))

(defn add-jars-index [trans]
  ;; speed up the front page and selects for jars by name
  (sql/db-do-commands trans
                      (str "CREATE INDEX IF NOT EXISTS jars_idx0 "
                        "ON jars (group_name, jar_name, created DESC)")))

(defn add-pgp-key [trans]
  (try
    (sql/db-do-commands trans "ALTER TABLE users ADD COLUMN pgp_key TEXT")
    (catch BatchUpdateException e
      ;; production db doesn't have this migration recorded, but has the field
      ;; ignore the dupe in that case
      (when-not (re-find #"duplicate column name" (.getMessage e))
        (throw e)))))

(defn add-added-by [trans]
  (sql/db-do-commands trans "ALTER TABLE groups ADD COLUMN added_by TEXT"))

(defn add-password-reset-code [trans]
  (sql/db-do-commands trans "ALTER TABLE users ADD COLUMN password_reset_code TEXT"))

(defn add-password-reset-code-created-at [trans]
  (sql/db-do-commands trans "ALTER TABLE users ADD COLUMN password_reset_code_created_at DATE"))

(defn add-licenses-and-packaging [trans]
  (sql/db-do-commands trans
                      "ALTER TABLE jars ADD COLUMN licenses TEXT"
                      "ALTER TABLE jars ADD COLUMN packaging TEXT"))

;; the deps table was lost from production at some point
(defn restore-deps-table [trans]
  (try
    (sql/db-do-commands trans
                        (str "CREATE TABLE deps (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                             "group_name TEXT NOT NULL, "
                             "jar_name TEXT NOT NULL, "
                             "version TEXT NOT NULL, "
                             "dep_group_name TEXT NOT NULL, "
                             "dep_jar_name TEXT NOT NULL, "
                             "dep_version TEXT NOT NULL);"))
    (catch BatchUpdateException _
      ;; will throw if table already exists
      )))

(defn add-scope [trans]
  (sql/db-do-commands trans "ALTER TABLE deps ADD COLUMN dep_scope TEXT"))

(defn drop-search-table-and-triggers [trans]
  (try
    (sql/db-do-commands trans
      "DROP TRIGGER insert_search"
      "DROP TRIGGER update_search"
      "DROP TABLE search")
    (catch BatchUpdateException _
      ;; they won't be there in test, where the db is built from clojars.sql
      )))

;; migrations mechanics

(defn run-and-record [migration trans]
  (println "Running migration:" (:name (meta migration)))
  (migration trans)
  (sql/insert! trans
               "migrations"
               [:name :created_at]
               [(str (:name (meta migration)))
                (Timestamp. (System/currentTimeMillis))]))

(def migrations
  [#'initial-schema
   #'add-promoted-field
   #'add-jars-index
   #'add-pgp-key
   #'add-added-by
   #'add-password-reset-code
   #'add-password-reset-code-created-at
   #'add-licenses-and-packaging
   #'restore-deps-table
   #'add-scope
   #'drop-search-table-and-triggers])

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

