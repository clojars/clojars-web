(ns clojars.main
  (:require [clojars
             [admin :as admin]
             [config :refer [config configure]]
             [errors :as errors :refer [->StdOutReporter multiple-reporters]]
             [system :as system]]
            [com.stuartsierra.component :as component]
            [meta-merge.core :refer [meta-merge]]
            [yeller.clojure.client :as yeller])
  (:gen-class))

(def prod-env
  {:app {:middleware []}})

(defn prod-system [config yeller]
  (-> (meta-merge config prod-env)
      system/new-system
      (assoc :error-reporter (multiple-reporters
                              (->StdOutReporter)
                              yeller))))

(defn -main [& args]
  (configure args)
  (println "clojars-web: enabling yeller client")
  (let [yeller (yeller/client {:token (:yeller-token config)
                               :environment (:yeller-environment config)})]
    (Thread/setDefaultUncaughtExceptionHandler yeller)
    (let [system (component/start (prod-system))]
      (println "clojars-web: starting jetty on" (str "http://" (:bind config) ":" (:port config)))
      (admin/init (get-in system [:db :spec])))))
