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

(def migrations
  [#'initial-schema])

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

