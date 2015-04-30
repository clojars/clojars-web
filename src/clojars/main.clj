(ns clojars.main
  (:require [clojars.scp]
            [ring.adapter.jetty :refer [run-jetty]]
            [clojars.web :refer [clojars-app]]
            [clojars.promote :as promote]
            [clojars.config :refer [config configure]]
            [clojars.admin :as admin])
  (:import com.martiansoftware.nailgun.NGServer
           java.net.InetAddress)
  (:gen-class))

(defn start-jetty []
  (when-let [port (:port config)]
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
  (start-jetty)
  (admin/init)
  (start-nailgun))

;; (def server (run-jetty #'clojars-app {:port 8080 :join? false}))
;; (.stop server)
