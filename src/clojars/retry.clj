(ns clojars.retry
  (:require
   [clojars.errors :as errors]))

(defn retry*
  [{:keys [n sleep jitter error-reporter]
    :or {sleep 1000
         jitter 20
         n 3}}
   f]
  (loop [i n]
    (if (> i 0)
      (let [result (try
                     (f)
                     (catch Exception t
                       t))]
        (if (instance? Exception result)
          (do (errors/report-error error-reporter result)
              (Thread/sleep (+ sleep (rand-int jitter)))
              (recur (dec i)))
          result))
      (throw (ex-info (format "Retry limit %s has been reached" n)
                      {:n n :sleep sleep :jitter jitter})))))

(defmacro retry
  [opts & body]
  `(retry* ~opts (fn [] ~@body)))
