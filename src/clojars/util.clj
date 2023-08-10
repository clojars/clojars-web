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

(defn distinct-by
  "Returns a lazy sequence of the elements in coll with duplicate
  values (based on passing each element to f) removed. First duplicate
  wins. Returns a stateful transducer when no collection is provided."
  ([f]
   (fn [rf]
     (let [seen (volatile! #{})]
       (fn
         ([] (rf))
         ([result] (rf result))
         ([result input]
          (let [key (f input)]
            (if (contains? @seen key)
              result
              (do (vswap! seen conj key)
                  (rf result input)))))))))
  ([f coll]
   (letfn [(iter [xs seen]
             (lazy-seq
              (loop [[head :as xs] (seq xs)
                     seen seen]
                (when (seq xs)
                  (let [tail (rest xs)
                        ident (f head)]
                    (if (seen ident)
                      (recur tail seen)
                      (cons head (iter tail (conj seen ident)))))))))]
     (iter coll #{}))))
