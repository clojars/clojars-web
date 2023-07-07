(ns clojars.main
  (:gen-class)
  (:require
   [clojars
    [admin :as admin]
    [config :as config]
    [errors :as err]
    [system :as system]]
   [com.stuartsierra.component :as component]
   [meta-merge.core :refer [meta-merge]]
   [raven-clj.core :as raven-clj]))

(def ^:private prod-env
  {:app {:middleware []}})

(defn- info [& msg]
  (apply println "clojars-web:" msg))

(defn- warn [& msg]
  (apply println "clojars-web: WARNING -" msg))

(defn- prod-system [config prod-reporter]
  (-> (meta-merge config prod-env)
      system/new-system
      (assoc :error-reporter prod-reporter)))

(defn- raven-reporter
  [{:as _config :keys [sentry-dsn]}]
  (if sentry-dsn
    (let [raven-reporter (err/raven-error-reporter {:dsn sentry-dsn})]
      (info "enabling raven-clj client dsn:project-id:" (:project-id (raven-clj/parse-dsn sentry-dsn)))
      raven-reporter)
    (warn "no :sentry-dsn set in config, errors won't be logged to Sentry")))

(defn- error-reporter [config]
  (if-some [raven-reporter (raven-reporter config)]
    (err/multiple-reporters
     (err/log-reporter)
     raven-reporter)
    (err/log-reporter)))

(defn -main [& _args]
  (try
    (alter-var-root #'config/*profile* (constantly "production"))
    (let [config (config/config)
          error-reporter (error-reporter config)
          system (component/start (prod-system config error-reporter))]
      (err/set-default-exception-handler error-reporter)
      (info "starting jetty on" (str "http://" (:bind config) ":" (:port config)))
      (admin/init (get-in system [:db :spec])
                  (:search system)
                  (:storage system)))
    (catch Throwable t
      (binding [*out* *err*]
        (println "Error during app startup:"))
      (.printStackTrace t)
      (System/exit 70))))
