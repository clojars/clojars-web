(ns clojars.main
  (:require [ring.adapter.jetty :refer [run-jetty]]
            [clojars.web :refer [clojars-app]]
            [clojars.promote :as promote]
            [clojars.config :refer [config configure]]
            [clojars.admin :as admin]
            [clojars.errors :as errors]
            [clojars.ring-servlet-patch :refer [patch-ring-servlet!]])
  (:gen-class))

(defn start-jetty [& [port]]
  (patch-ring-servlet!)
  (when-let [port (or port (:port config))]
    (println "clojars-web: starting jetty on" (str "http://" (:bind config) ":" port))
    (run-jetty #'clojars-app {:host (:bind config)
                            :port port
                            :join? false})))

(defn -main [& args]
  (alter-var-root #'*read-eval* (constantly false))
  (configure args)
  (errors/register-global-exception-handler!)
  (start-jetty)
  (admin/init))

(comment
  (def server (start-jetty 8080))
  (.stop server))
