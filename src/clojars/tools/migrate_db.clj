(ns clojars.tools.migrate-db
  (:require [clojars.config :refer [config]]
            [clojars.db.migrate :refer [migrate]])
  (:gen-class))

(defn -main [& _]
  (let [db (:db (config))]
    (println "=> Migrating db")
    (migrate db)))
