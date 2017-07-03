(ns clojars.errors
  (:require [raven-clj.core :as raven-clj]
            [raven-clj.interfaces :as interfaces]
            [clojure.walk :as walk]
            [clj-stacktrace.repl :refer [pst]]
            [clojars.web.error-page :as error-page]
            [clojars.web.error-api :as error-api])
  (:import java.util.UUID))


(defn raven-extra-data [e extra]
  (-> (ex-data e)
    (merge extra)
    (dissoc :message)))

(defn raven-event-info [id message e extra]
  (cond-> {}
          message (assoc :message message)
          e (interfaces/stacktrace e ["clojars"])
          (or e extra) (assoc :extra (raven-extra-data e extra))
          id (assoc-in [:extra :error-id] id)))

(defn raven-error-report
  ([dsn id message e extra]
   (raven-clj/capture dsn (raven-event-info id message e extra)))
  ([dsn id message e]
   (raven-error-report dsn id message e nil))
  ([dsn id message]
   (raven-error-report dsn id message nil nil)))

(defprotocol ErrorReporter
  (-report-error [reporter e extra id]))

(defrecord RavenErrorReporter [raven-config]
   ErrorReporter
   (-report-error [_ e extra id]
     (raven-error-report (:dsn raven-config)
                         id
                         (or (:message extra) "RavenErrorReporter capture")
                         e
                         extra))
 
   Thread$UncaughtExceptionHandler
   (uncaughtException [this thread throwable]
     (raven-error-report (:dsn raven-config)
                         nil
                         ("UncaughtExceptionHandler capture")
                         throwable)))
 
(defn raven-error-reporter [raven-config]
  (->RavenErrorReporter raven-config))

(defrecord StdOutReporter []
  ErrorReporter
  (-report-error [_ e _ id]
    (println "ERROR ID:" id)
    (pst e)))

(defn stdout-reporter []
  (->StdOutReporter))

(defrecord NullReporter []
  ErrorReporter
  (-report-error [_ _ _ _]))

(defn null-reporter []
  (->NullReporter))

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
