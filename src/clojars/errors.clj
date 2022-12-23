(ns clojars.errors
  (:require
   [clj-stacktrace.repl :refer [pst]]
   [clojars.log :as log]
   [clojars.web.error-api :as error-api]
   [clojars.web.error-page :as error-page]
   [raven-clj.core :as raven-clj]
   [raven-clj.interfaces :as interfaces]))

(defn raven-extra-data [e extra]
  (-> (ex-data e)
      (merge extra)
      (dissoc :message)))

(defn raven-event-info [id message e extra]
  (cond-> {}
    message (assoc :message message)
    e (interfaces/stacktrace e ["clojars"])
    (or e extra) (assoc :extra (raven-extra-data e extra))
    id (assoc-in [:extra :error-id] (str id))))

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
  (uncaughtException [_this _thread throwable]
    (raven-error-report (:dsn raven-config)
                        nil
                        "UncaughtExceptionHandler capture"
                        throwable)))

(defn raven-error-reporter [raven-config]
  (->RavenErrorReporter raven-config))

(defrecord LogReporter []
  ErrorReporter
  (-report-error [_ e _ id]
    (log/error {:tag ::uncaught-error
                :trace-id id
                :error e})))

(defn log-reporter []
  (->LogReporter))

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
  (-report-error [_reporter e extra id]
    (run! #(-report-error % e extra id) reporters)))

(defn multiple-reporters [& reporters]
  (->MultiReporter reporters))

(defn report-error
  ([reporter e]
   (report-error reporter e nil nil))
  ([reporter e extra id]
   (when-not (false? (:report? (ex-data e)))
     (-report-error reporter e extra id))
   id))

(defn report-ring-error [reporter e request id]
  (report-error reporter
                e
                (-> {:message "Ring caught an exception"}
                    (interfaces/http request log/redact))
                id))

(defn wrap-exceptions [app reporter]
  (fn [req]
    (let [request-id (log/trace-id)]
      (try
        (log/with-context {:trace-id request-id}
          (app req))
        (catch Throwable t
          (let [params (:params req)
                err-response-fn (if (= (:format params) "json")
                                  error-api/error-api-response
                                  error-page/error-page-response)]
            (->> (report-ring-error reporter t req request-id)
                 (err-response-fn (ex-data t)))))))))
