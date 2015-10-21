(ns clojars.main
  (:require [clojars
             [admin :as admin]
             [config :refer [config configure]]
             [errors :as errors]
             [ring-servlet-patch :as patch]
             [web :refer [clojars-app]]]
            [ring.adapter.jetty :refer [run-jetty]])
  (:import [com.zaxxer.hikari HikariConfig HikariDataSource])
  (:gen-class))

(defn start-jetty [db & [port]]
  (when-let [port (or port (:port config))]
    (println "clojars-web: starting jetty on" (str "http://" (:bind config) ":" port))
    (run-jetty (#'clojars-app db)
               {:host (:bind config)
                :port port
                :join? false
                :configurator patch/use-status-message-header})))

(defn jdbc-url [db-config]
  (if (string? db-config)
    (if (.startsWith db-config "jdbc:")
      db-config
      (str "jdbc:" db-config))
    (let [{:keys [subprotocol subname]} db-config]
      (format "jdbc:%s:%s" subprotocol subname))))

(defn -main [& args]
  (alter-var-root #'*read-eval* (constantly false))
  (configure args)
  (errors/register-global-exception-handler!)
  (let [url (jdbc-url (:db config))
        db {:datasource (HikariDataSource. (doto (HikariConfig.) (.setJdbcUrl url)))}]
    (start-jetty db)
    (admin/init db)))

(comment
  (def server (start-jetty 8080))
  (.stop server))
