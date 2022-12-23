(ns clojars.tools.migrate-db
  (:gen-class)
  (:require
   [clojars.config :refer [config]]
   [clojars.db.migrate :refer [migrate]]))

(defn -main [& _]
  (let [db (:db (config))]
    (println "=> Migrating db")
    (migrate db)))
