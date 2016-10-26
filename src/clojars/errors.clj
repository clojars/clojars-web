(ns clojars.errors
  (:require [raven-clj.core :as raven-clj]
            [raven-clj.interfaces :as interfaces]
            [clojure.walk :as walk]
            [clj-stacktrace.repl :refer [pst]]
            [clojars.web.error-page :as error-page]
            [clojars.web.error-api :as error-api])
  (:import java.util.UUID))


(defn raven-event-info [message e extra]
  (cond-> {}
          message (assoc :message message)
          e (interfaces/stacktrace e ["clojars"])
          extra (merge extra)))

(defn raven-error-report
  ([dsn message e extra]
   (raven-clj/capture dsn (raven-event-info message e extra)))
  ([dsn message e]
   (raven-clj/capture dsn (raven-event-info message e nil)))
  ([dsn message]
   (raven-clj/capture dsn (raven-event-info message nil nil))))

(defprotocol ErrorReporter
  (-report-error [reporter e extra id]))

(defrecord RavenErrorReporter [raven-config]
   ErrorReporter
   (-report-error [_ e extra id]
     (raven-error-report (:dsn raven-config)
                         (or (:message extra) "RavenErrorReporter capture")
                         e
                         (merge extra {:extra {:error-id id}})))
 
   Thread$UncaughtExceptionHandler
   (uncaughtException [this thread throwable]
     (raven-error-report (:dsn raven-config)
                         ("UncaughtExceptionHandler capture")
                         throwable)))
 
(defn raven-error-reporter [raven-config]
  (->RavenErrorReporter raven-config))

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

(defn replace-kv
  "Walk form looking for map entries with the keyword kw and, if found, replace the entry with replacement"
  [kw replacement form]
  (walk/postwalk
    (fn [original]
      (if (and (vector? original) (= (count original) 2) (= kw (first original)))
        replacement
        original))
    form))

(defn alter-fn
  "Function called by raven-clj.interfaces.http to scrub data from the passed http-info map.
   Returns the scrubbed http-info map.
   See also: https://docs.sentry.io/clientdev/interfaces/#special-interfaces"
  [http-event-map]
  (->> http-event-map
       (replace-kv :password [:password "SCRUBBED"])
       (replace-kv :confirm [:confirm "SCRUBBED"]))) ; TODO: add more? auth tokens?

(defn report-ring-error [reporter e request]
 (report-error reporter
   e
   (-> {:message "Ring caught an exception"}
     (interfaces/http request alter-fn))))

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
