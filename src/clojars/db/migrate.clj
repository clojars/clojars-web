(ns clojars.db.migrate
  (:require [clojure.java.jdbc :as sql]
            [clojure.java.io :as io]
            [clojars.config :refer [config]])
  (:import (java.sql Timestamp)))

(defn initial-schema []
  (doseq [cmd (.split (slurp "clojars.sql") ";\n\n")]
    ;; needs to succeed even if tables exist since this migration
    ;; hasn't been recorded in extant DBs before migrations were introduced
    (try (sql/do-commands cmd)
         (catch java.sql.BatchUpdateException _))))

(defn add-promoted-field []
  (sql/do-commands "ALTER TABLE jars ADD COLUMN promoted_at DATE"))

(defn add-jars-index []
  ;; speed up the front page and selects for jars by name
  (sql/do-commands (str "CREATE INDEX IF NOT EXISTS jars_idx0 "
                        "ON jars (group_name, jar_name, created DESC)")))

;; migrations mechanics

(defn run-and-record [migration]
  (println "Running migration:" (:name (meta migration)))
  (migration)
  (sql/insert-values "migrations" [:name :created_at]
                     [(str (:name (meta migration)))
                      (Timestamp. (System/currentTimeMillis))]))

(defn- ensure-db-directory-exists [db]
  (when-not (.exists (io/file db))
    (.mkdirs (.getParentFile (io/file db)))))

(defn migrate [& migrations]
  (ensure-db-directory-exists (:subname (config :db)))
  (sql/with-connection (config :db)
    (try (sql/create-table "migrations"
                           [:name :varchar "NOT NULL"]
                           [:created_at :timestamp
                            "NOT NULL"  "DEFAULT CURRENT_TIMESTAMP"])
         (catch Exception _))
    (sql/transaction
     (let [has-run? (sql/with-query-results run ["SELECT name FROM migrations"]
                      (set (map :name run)))]
       (doseq [m migrations
               :when (not (has-run? (str (:name (meta m)))))]
         (run-and-record m))))))

(defn -main []
  (migrate #'initial-schema
           #'add-promoted-field
           #'add-jars-index))
