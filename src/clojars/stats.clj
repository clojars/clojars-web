(ns clojars.stats
  (:require [clojars.s3 :as s3]
            [clojure.core.memoize :as memo]
            [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import java.io.PushbackReader
           (java.text DecimalFormat)))

(defprotocol Stats
  (download-count
    [t group-id artifact-id]
    [t group-id artifact-id version])
  (total-downloads [t]))

(defn- download-all-stats
  "Pulls the all.edn stats file from s3 and returns them as a map."
  [s3 bucket-name]
  (with-open [rdr (-> (s3/get-object-stream s3 bucket-name "all.edn")
                      (io/reader)
                      (PushbackReader.))]
    (edn/read rdr)))

(def all (memo/ttl download-all-stats :ttl/threshold (* 60 60 1000))) ;; 1 hour

(defrecord ArtifactStats [s3 bucket-name]
  Stats
  (download-count [_ group-id artifact-id]
    (->> (get (all s3 bucket-name) [group-id artifact-id])
         (vals)
          (reduce +)))
  (download-count [_ group-id artifact-id version]
    (get-in (all s3 bucket-name) [[group-id artifact-id] version] 0))
  (total-downloads [_]
    (->> (all s3 bucket-name)
         (vals)
         (mapcat vals)
         (reduce +))))

(defn artifact-stats
  "Returns a stats implementation for artifact stats.
  Does not have an an s3 client assoc'ed, that should be done via
  component/using or system/using."
  [bucket-name]
  (map->ArtifactStats {:bucket-name bucket-name}))

(defn format-stats [num]
  (.format (DecimalFormat. "#,##0") num))
