(ns clojars.errors
  (:require [yeller.clojure.ring :as yeller-ring]
            [yeller.clojure.client :as yeller]
            [clojars.config :refer [config]]
            [clj-stacktrace.repl :refer [pst]]
            [clojars.web.error-page :as error-page])
  (:import java.util.UUID
           com.yellerapp.client.YellerHTTPClient))


(defprotocol ErrorReporter
  (-report-error [reporter e extra id]))

(extend-protocol ErrorReporter
  YellerHTTPClient
  (-report-error [client e extra id]
    (yeller/report client e (assoc-in extra [:custom-data :error-id] id))))

(defrecord StdOutReporter []
  ErrorReporter
  (-report-error [_ e _ id]
    (println "ERROR ID:" id)
    (pst e)))

(defrecord MultiReporter [reporters]
  ErrorReporter
  (-report-error [reporter e extra id]
    (run! #(-report-error % e extra id) reporters)))

(defn multiple-reporters [& reporters]
  (->MultiReporter reporters))

(defn error-id []
  (str (UUID/randomUUID)))

(defn report-error
  ([reporter e]
   (report-error reporter e nil))
  ([reporter e extra]
   (let [id (error-id)]
     (-report-error reporter e extra id)
     id)))

(defn report-ring-error [reporter e request]
  (report-error reporter e (yeller-ring/format-extra nil request)))

(defn wrap-exceptions [app reporter]
  (fn [req]
    (try
      (app req)
      (catch Throwable t
        (->> (report-ring-error reporter t req)
             (error-page/error-page-response))))))
