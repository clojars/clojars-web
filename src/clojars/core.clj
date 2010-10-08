(ns clojars.core
  (:use compojure
        [clojars.web :only [clojars-app]])
  (:require clojars.scp)
  (:import com.martiansoftware.nailgun.NGServer
           java.net.InetAddress)
  (:gen-class))

(defn -main [& [http-port ng-port]]
  (if (not (and http-port ng-port))
    (.println System/err "Usage: clojars.core http-port nailgun-port")
    (.println System/err "   eg: clojars.core 8080 8701")
    (do
      (println "clojars-web: starting jetty on port" http-port)
      (run-server {:port (Integer/parseInt http-port)}
                  "/*" (servlet clojars-app))
      (println "clojars-web: starting nailgun on 127.0.0.1 port " ng-port)
      (.run (NGServer. (InetAddress/getByName "127.0.0.1")
                       (Integer/parseInt ng-port))))))
