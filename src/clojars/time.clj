(ns clojars.time
  (:import
   (java.time
    Instant)
   (java.time.temporal
    ChronoUnit)))

(set! *warn-on-reflection* true)

(defn now
  "Returns the current time as a j.t.Instant."
  ^Instant []
  (Instant/now))

(defmacro with-now
  "Use in tests to set the value returned by `now`."
  [now & body]
  `(with-redefs [now (constantly ~now)]
     ~@body))

(defn days-ago
  (^Instant [days]
   (days-ago (now) days))
  (^Instant [^Instant instant ^long days]
   (.minus instant days ChronoUnit/DAYS)))

(defn days-from
  ([days]
   (days-from (now) days))
  ([^Instant instant ^long days]
   (.plus instant days ChronoUnit/DAYS)))

(defn parse-instant
  ^Instant [^String instant-string]
  (Instant/parse instant-string))
