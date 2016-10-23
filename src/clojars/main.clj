(ns clojars.main
  (:require [clojars
             [admin :as admin]
             [cloudfiles :as cf]
             [config :refer [config configure]]
             [errors :refer [->StdOutReporter raven-error-reporter multiple-reporters]]
             [system :as system]]
            [com.stuartsierra.component :as component]
            [meta-merge.core :refer [meta-merge]]
            [raven-clj.core :as raven-clj])
  (:gen-class))

(def prod-env
  {:app {:middleware []}})

(defn prod-system [config raven-reporter]
  (-> (meta-merge config prod-env)
      system/new-system
      (assoc
        :error-reporter (multiple-reporters
                          (->StdOutReporter)
                          raven-reporter)
        :cloudfiles     (cf/connect
                          (:cloudfiles-user config)
                          (:cloudfiles-token config)
                          (:cloudfiles-container config)))))

(defn -main [& args]
  (try
    (configure args)
    (let [dsn (:sentry-dsn config)]
      (if (not dsn)
        (throw (Exception. (str "No :sentry-dsn key value found in config - " config))))
      (println "clojars-web: enabling raven-clj client dsn:project-id:" (:project-id (raven-clj/parse-dsn dsn)))
      (let [raven-reporter (raven-error-reporter {:dsn dsn})]
        (Thread/setDefaultUncaughtExceptionHandler raven-reporter)
        (let [system (component/start (prod-system config raven-reporter))]
          (println "clojars-web: starting jetty on" (str "http://" (:bind config) ":" (:port config)))
          (admin/init (get-in system [:db :spec])
                      (:queue system)
                      (:search system)
                      (:storage system)))))
    (catch Throwable t
      (binding [*out* *err*]
        (println "Error during app startup:"))
      (.printStackTrace t))))
