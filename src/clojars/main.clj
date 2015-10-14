(ns clojars.main
  (:require [clojars
             [admin :as admin]
             [config :refer [config configure]]
             [errors :as errors]
             [system :as system]
             [web :refer [clojars-app]]]
            [meta-merge.core :refer [meta-merge]]
            [com.stuartsierra.component :as component])
  (:gen-class))

(def prod-env
  {:app {:middleware []}})

(defn -main [& args]
  (configure args)
  (errors/register-global-exception-handler!)
  (println "clojars-web: starting jetty on" (str "http://" (:bind config) ":" (:port config)))
  (let [system (component/start (system/new-system (meta-merge config prod-env)))]
    (admin/init (get-in system [:db :spec]))))
