(ns clojars.util
  (:refer-clojure :exclude [parse-long]))

(defn filter-some
  "Returns the first x in coll where (pred x) returns logical true, else nil."
  [pred coll]
  (some #(when (pred %) %) coll))

(defn parse-long [s]
  (when-not (#{nil "" "-"} s)
    (try (Long/parseLong s)
         (catch NumberFormatException _))))

(defn concatv
  "An eager version of concat, returning a vector."
  ([] [])
  ([a] (vec a))
  ([a b] (into (vec a) b))
  ([a b & colls]
   (into (concatv a b) cat colls)))
