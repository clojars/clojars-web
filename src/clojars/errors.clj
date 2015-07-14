(ns clojars.errors
  (:require [yeller.clojure.ring :as yeller-ring]
            [yeller.clojure.client :as yeller]
            [clojars.config :refer [config]]
            [clj-stacktrace.repl :refer [pst]]
            [clojars.web.error-page :as error-page])
  (:import java.util.UUID))

(def client
  (delay
    (when-let [token (:yeller-token config)]
      (println "clojars-web: enabling yeller client")
      (yeller/client {:token token}))))

(defn error-id []
  (str (UUID/randomUUID)))

(defn report-error
  ([e]
   (report-error e nil))
  ([e extra]
   (let [id (error-id)]
     (println "ERROR ID:" id)
     (pst e)
     (when-let [c @client]
       (yeller/report c e
         (-> extra
           (assoc :environment (:yeller-environment config))
           (assoc-in [:custom-data :error-id] id))))
     id)))

(defn report-ring-error [e request]
  (report-error e (yeller-ring/format-extra nil request)))

(defn wrap-exceptions [app]
  (fn [req]
    (try
      (app req)
      (catch Throwable t
        (->> (report-ring-error t req)
          (error-page/error-page-response))))))

(defn register-global-exception-handler! []
  (when-let [c @client]
    (Thread/setDefaultUncaughtExceptionHandler c)))
