(ns clojars.main
  (:use [ring.adapter.jetty :only [run-jetty]]
        [clojars.web :only [clojars-app]])
  (:require [clojars.scp])
  (:import com.martiansoftware.nailgun.NGServer
           java.net.InetAddress)
  (:gen-class))

(defn -main
  ([]
     (.println System/err "Usage: clojars.main http-port nailgun-port")
     (.println System/err "   eg: clojars.main 8080 8701")
     (System/exit 1))
  ([http-port ng-port]
     (println "clojars-web: starting jetty on port" http-port)
     (run-jetty clojars-app {:port (Integer/parseInt http-port) :join? false})
     (println "clojars-web: starting nailgun on 127.0.0.1 port " ng-port)
     (.run (NGServer. (InetAddress/getByName "127.0.0.1")
                      (Integer/parseInt ng-port)))))

; (defonce server (run-jetty #'clojars-app {:port 8080 :join? false}))

