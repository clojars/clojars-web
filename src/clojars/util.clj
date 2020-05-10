(ns clojars.util)

(defn filter-some
  "Returns the first x in coll where (pred x) returns logical true, else nil."
  [pred coll]
  (some #(when (pred %) %) coll))
