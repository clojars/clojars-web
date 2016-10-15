(ns clojars.errors
  (:require [raven-clj.core :as raven-clj]
            [raven-clj.interfaces :as interfaces]
            [clojars.config :refer [config]]
            [clj-stacktrace.repl :refer [pst]]
            [clojars.web.error-page :as error-page]
            [clojars.web.error-api :as error-api])
  (:import java.util.UUID))


(defprotocol ErrorReporter
  (-report-error [reporter e extra id]))

(defrecord RavenErrorReporter [raven-config]
  ErrorReporter
  (-report-error [_ e _ id]
    (raven-clj/capture (:dsn raven-config)
                       (-> {:message "RavenErrorReporter capture" :error-id id}
                           (interfaces/stacktrace e))))

  java.lang.Thread$UncaughtExceptionHandler
  (uncaughtException [_ thread throwable]
    (raven-clj/capture (:dsn raven-config)
                       (-> {:message "UncaughtExceptionHandler capture"}
                           (interfaces/stacktrace throwable)))))

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
     (when-not (false? (:report? (ex-data e)))
       (-report-error reporter e extra id))
     id)))

(defn alter-fn [request]
  ;(dissoc request :data)) ; TODO:
  request)

(defn report-ring-error [reporter e request]
  (raven-clj/capture (:dsn reporter)
                     (-> {:message "Ring reported an error"}
                         (interfaces/stacktrace e ["clojars"])
                         (interfaces/http request alter-fn))))

; Note: I did not use raven-ring because this exisitng logic wraps ring exceptions
; and also handles user error reporting, etc. I left the implementation as close
; to the original yeller code as possible - just to keep the changes to a minimum.
; See also: [raven-clj.ring :as raven-ring]

(defn wrap-exceptions [app reporter]
  (fn [req]
    (try
      (app req)
      (catch Throwable t
        (let [params (:params req)
              err-response-fn (if (= (:format params) "json")
                                error-api/error-api-response
                                error-page/error-page-response)]
          (->> (report-ring-error reporter t req)
               (err-response-fn (ex-data t))))))))
