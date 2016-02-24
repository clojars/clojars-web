(ns clojars.errors
  (:require [yeller.clojure.ring :as yeller-ring]
            [yeller.clojure.client :as yeller]
            [clojars.config :refer [config]]
            [clj-stacktrace.repl :refer [pst]]
            [clojars.web.error-page :as error-page]
            [clojars.web.error-api :as error-api])
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
     (when-not (false? (:report? (ex-data e)))
       (-report-error reporter e extra id))
     id)))

(defn report-ring-error [reporter e request]
  (report-error reporter e (yeller-ring/format-extra nil request)))

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
