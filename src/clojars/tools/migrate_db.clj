(ns clojars.tools.migrate-db
  (:gen-class)
  (:require
   [clojars.config :as config]
   [clojars.db.migrate :refer [migrate]]))

(defn -main [env]
  (printf "\n=> Migrating %s db\n" env)
  (flush)
  (let [db (:db (config/config (keyword env)))]
    (migrate db)))
