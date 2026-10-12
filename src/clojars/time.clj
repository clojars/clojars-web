(ns clojars.time
  (:import
   (java.time
    Duration
    Instant)
   (java.time.temporal
    ChronoUnit)
   (java.util
    Date)))

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

(defn seconds-from
  [^Instant instant ^long seconds]
  (.plus instant seconds ChronoUnit/SECONDS))

(defn parse-instant
  ^Instant [^String instant-string]
  (Instant/parse instant-string))

(defn seconds-between
  [^Instant i1 ^Instant i2]
  (.get (Duration/between i1 i2)
        ChronoUnit/SECONDS))

(defn date-after
  [^Date d1 ^Date d2]
  (.after d1 d2))
