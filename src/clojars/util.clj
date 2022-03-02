(ns clojars.util)

(defn filter-some
  "Returns the first x in coll where (pred x) returns logical true, else nil."
  [pred coll]
  (some #(when (pred %) %) coll))

(defn parse-long [s]
  (when-not (#{nil "" "-"} s)
    (try (Long/parseLong s)
         (catch NumberFormatException _))))

(defn assoc-some
  "Like clojure.core/assoc but does not assoc keys with nil values."
  [m & keyvals]
  (assert (even? (count keyvals)))
  (with-meta
    (persistent!
     (reduce (fn [m [k v]]
               (if (some? v)
                 (assoc! m k v)
                 m))
             (transient (or m {}))
             (partition 2 keyvals)))
    (meta m)))
