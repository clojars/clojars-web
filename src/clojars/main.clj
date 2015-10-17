(ns clojars.main
  (:require [clojars
             [admin :as admin]
             [config :refer [config configure]]
             [errors :as errors]
             [ring-servlet-patch :as patch]
             [web :refer [clojars-app]]]
            [ring.adapter.jetty :refer [run-jetty]])
  (:gen-class))

(defn start-jetty [db & [port]]
  (when-let [port (or port (:port config))]
    (println "clojars-web: starting jetty on" (str "http://" (:bind config) ":" port))
    (run-jetty (#'clojars-app db)
               {:host (:bind config)
                :port port
                :join? false
                :configurator patch/use-status-message-header})))

(defn -main [& args]
  (alter-var-root #'*read-eval* (constantly false))
  (configure args)
  (errors/register-global-exception-handler!)
  (let [db (:db config)]
    (start-jetty db)
    (admin/init db)))

(comment
  (def server (start-jetty 8080))
  (.stop server))
