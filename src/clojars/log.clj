(ns clojars.log
  "Provides redacted edn logging via clojure.tools.logging."
  (:require
   [clojure.tools.logging.readable :as log])
  (:import java.util.UUID))

(set! *warn-on-reflection* true)

(def ^:private redacted-keys
  #{:confirm
    :current-password
    :password})

(defprotocol Redact
  (redact [v]))

(extend-protocol Redact
  nil
  (redact [v] v)

  Object
  (redact [v] v)

  clojure.lang.Symbol
  (redact [v]
    ;; Resolve symbols to values since this is used within a macro
    ;; context. This means that we won't be able to log quoted symbols
    ;; as symbols, but that's ok!
    `(redact ~v))

  java.util.List
  (redact [v]
    (vec (map redact v)))

  java.util.Map
  (redact [v]
    (reduce-kv
     (fn [m k v]
       (if (redacted-keys k)
         (assoc m k "<REDACTED>")
         (-> m
             (dissoc k)
             (assoc (redact k) (redact v)))))
     {}
     v))

  clojure.lang.ExceptionInfo
  (redact [v]
    (doto ^Exception (ex-info (ex-message v)
                              (redact (ex-data v))
                              (redact (ex-cause v)))
      (.setStackTrace (.getStackTrace v)))))

(def ^:dynamic *context* nil)

(defmacro with-context
  "Binds a context that will be included with any logged maps. `ctx` is
  merged into any existing context."
  [ctx & body]
  `(binding [*context* (merge *context* ~ctx)]
     ~@body))

(defn trace-id
  []
  (UUID/randomUUID))

(defmacro error
  "Logs a map of data as edn at the ERROR level."
  [m]
  `(log/error (redact (merge *context* ~m))))

(defmacro info
  "Logs a map of data as edn at the INFO level."
  [m]
  `(log/info (redact (merge *context* ~m))))

(defmacro warn
  "Logs a map of data as edn at the WARN level."
  [m]
  `(log/warn (redact (merge *context* ~m))))

(defn wrap-request-context
  "Ring middleware that adds details about the request to the logging context."
  [f]
  (fn [req]
    (with-context (select-keys req [:request-method :uri])
      (f req))))
