(ns clojars.main
  (:use [ring.adapter.jetty :only [run-jetty]]
        [clojars.web :only [clojars-app]]
        [clojars.config :only [config configure]])
  (:require [clojars.scp])
  (:import com.martiansoftware.nailgun.NGServer
           java.net.InetAddress)
  (:gen-class))

(defn start-jetty []
  (when-let [port (:port config)]
    (println "clojars-web: starting jetty on" (str "http://" (:bind config) ":" port))
    (run-jetty clojars-app {:host (:bind config)
                            :port port
                            :join? false})))

(defn start-nailgun []
  (when-let [port (:nailgun-port config)]
    (println "clojars-web: starting nailgun on" (str (:nailgun-bind config) ":" port))
    (.run (NGServer. (InetAddress/getByName (:nailgun-bin config)) port))))

(defn -main [& args]
  (configure args)
  (start-jetty)
  (start-nailgun))

; (defonce server (run-jetty #'clojars-app {:port 8080 :join? false}))

