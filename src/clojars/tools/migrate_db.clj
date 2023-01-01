(ns clojars.tools.migrate-db
  (:gen-class)
  (:require
   [clojars.config :as config]
   [clojars.db.migrate :refer [migrate]]))

(defn -main [& [env]]
  (binding [config/*profile* (if env
                               env
                               "development")]
    (let [db (:db (config/config))]
      (printf "=> Migrating %s db\n" config/*profile*)
      (migrate db))))
