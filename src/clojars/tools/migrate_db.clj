(ns clojars.tools.migrate-db
  (:require [clojars.config :refer [config configure]]
            [clojars.db.migrate :refer [migrate]]
            [clojure.java.io :as io])
  (:gen-class))

(defn- ensure-db-directory-exists [db]
  (when-not (.exists (io/file db))
    (.mkdirs (.getParentFile (io/file db)))))

(defn -main [& _]
  (configure nil)
  (let [db (:db config)]
    (println "=> Migrating" db)
    (ensure-db-directory-exists (:subname db))
    (migrate db)))
