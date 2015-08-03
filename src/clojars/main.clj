(ns clojars.main
  (:require [clojars.scp]
            [ring.adapter.jetty :refer [run-jetty]]
            [clojars.web :refer [clojars-app]]
            [clojars.promote :as promote]
            [clojars.config :refer [config configure]]
            [clojars.admin :as admin]
            [clojars.errors :as errors]
            [clojars.ring-servlet-patch :refer [patch-ring-servlet!]])
  (:import com.martiansoftware.nailgun.NGServer
           java.net.InetAddress)
  (:gen-class))

(defn start-jetty [& [port]]
  (patch-ring-servlet!)
  (when-let [port (or port (:port config))]
    (println "clojars-web: starting jetty on" (str "http://" (:bind config) ":" port))
    (run-jetty #'clojars-app {:host (:bind config)
                            :port port
                            :join? false})))

(defn start-nailgun []
  (when-let [port (:nailgun-port config)]
    (println "clojars-web: starting nailgun on" (str (:nailgun-bind config) ":" port))
    (.run (NGServer. (InetAddress/getByName (:nailgun-bind config)) port))))

(defn -main [& args]
  (alter-var-root #'*read-eval* (constantly false))
  (configure args)
  (errors/register-global-exception-handler!)
  (start-jetty)
  (admin/init)
  (start-nailgun))

(comment
  (def server (start-jetty 8080))
  (.stop server))
