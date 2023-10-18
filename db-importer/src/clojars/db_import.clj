(ns clojars.db-import
  (:require
   [clojars.db :as db]
   [clojure.set :as set]
   [clojure.walk :as walk]
   [next.jdbc.sql :as sql]))

(def tables
  [:users
   :jars
   :groups
   :deps])

(defn select-all
  [db table]
  (sql/query
   db
   [(format "select * from %s" (name table))]))

(defn clear-psql-db
  [psql-db]
  (db/do-commands
   psql-db
   (mapv
    #(format "delete from %s" (name %))
    tables)))

(defn translate-date
  [d]
  (if (and d (int? d))
    (java.sql.Timestamp. d)
    d))

(def date-fields
  #{:created
    :password_reset_code_created_at})

(def boolean-fields
  #{:admin :inactive})

(def fields-to-drop
  #{:promoted_at
    :salt
    :ssh_key
    :pgp_key})

(defn convert-booleans
  [v]
  (if (and (map-entry? v)
           (contains? boolean-fields (key v)))
    [(key v) (= 1 (val v))]
    v))

(defn convert-dates
  [v]
  (if (and (map-entry? v)
           (contains? date-fields (key v)))
    [(key v) (translate-date (val v))]
    v))

(defn drop-fields
  [v]
  (if (map? v)
    (select-keys v (set/difference (set (keys v)) fields-to-drop))
    v))

(defn translate-row
  [row]
  (-> (walk/postwalk
       (comp convert-dates
             convert-booleans
             drop-fields)
       row)
      (walk/stringify-keys)
      ;; user columns have to be quoted since user is a reserved word
      ;; in postgres
      (set/rename-keys {"user" "\"user\""})))

(defn insert
  [db table data]
  (doseq [chunk (partition-all 10000 data)]
    (printf "==> inserting batch of %s\n" (count chunk))
    (sql/insert-multi! db table
                       (map translate-row chunk))))

(defn existing-ids
  [db table]
  (into #{}
        (map :id)
        (sql/query db
                   [(format "select id from %s" (name table))])))

(defn -main
  [& [sqlite-db-path pg-host pg-port pg-user pg-password :as args]]
  (when (not= 5 (count args))
    (throw (ex-info "Usage: clj -m clojars.db-import sqlite-db-path pg-host pg-port pg-user pg-password"
                    {:args args})))

  (let [sqlite-db {:classname "org.sqlite.JDBC"
                   :subname sqlite-db-path
                   :subprotocol "sqlite"}
        psql-db {:dbtype "postgresql"
                 :dbname "clojars"
                 :host pg-host
                 :port pg-port
                 :user pg-user
                 :password pg-password}]

    (println "NOTE: this will import any data from" sqlite-db-path
             "in to" (format "%s:%s (db: clojars)" pg-host pg-port)
             "that hasn't already been imported.\n")
    (print "Are you sure you want to continue? [y/N] ")
    (flush)
    (when-not (= "y" (.toLowerCase (read-line)))
      (println "Aborting.")
      (throw (ex-info "Aborting" {})))
    (println)
    (doseq [table tables]
      (println "importing" table)
      (let [data (select-all sqlite-db table)
            existing-ids (existing-ids psql-db table)
            filtered-data (remove #(existing-ids (:id %)) data)]
        (printf "=> %s rows\n" (count filtered-data))
        (insert psql-db table filtered-data)))))
