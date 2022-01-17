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

(defn- read-all-stats
  [stream]
  (with-open [rdr (-> stream
                      (io/reader)
                      (PushbackReader.))]
    (edn/read rdr)))

(defn- download-all-stats
  "Pulls the all.edn stats file from s3 and returns them as a map."
  [s3-bucket]
  (read-all-stats (s3/get-object-stream s3-bucket "all.edn")))

(def ^:private stats-cache-ttl (* 60 60 1000)) ;; 1 hour

(def all (memo/ttl download-all-stats :ttl/threshold stats-cache-ttl))

(defn- calc-total-downloads
  [s3-bucket]
    (->> (all s3-bucket)
         (vals)
         (mapcat vals)
         (reduce +)))

(def all-total-downloads (memo/ttl calc-total-downloads :ttl/threshold stats-cache-ttl))

(defrecord ArtifactStats [stats-bucket]
  Stats
  (download-count [_ group-id artifact-id]
    (->> (get (all stats-bucket) [group-id artifact-id])
         (vals)
          (reduce +)))
  (download-count [_ group-id artifact-id version]
    (get-in (all stats-bucket) [[group-id artifact-id] version] 0))
  (total-downloads [_]
    (all-total-downloads stats-bucket)))

(defn artifact-stats
  "Returns a stats implementation for artifact stats read from s3.
  Does not have an an s3 bucket client assoc'ed, that should be done
  via component/using or system/using."
  []
  (map->ArtifactStats {}))

(defrecord LocalArtifactStats [stats-file]
  Stats
  (download-count [_ group-id artifact-id]
    (->> (get (read-all-stats (io/input-stream stats-file)) [group-id artifact-id])
         (vals)
          (reduce +)))
  (download-count [_ group-id artifact-id version]
    (get-in (read-all-stats (io/input-stream stats-file))
            [[group-id artifact-id] version]
            0))
  (total-downloads [_]
    (->> (read-all-stats stats-file)
         (vals)
         (mapcat vals)
         (reduce +))))

(defn local-artifact-stats
  "Returns a stats implementation for artifact stats that reads from a local file."
  [stats-file]
  (->LocalArtifactStats stats-file))

(defn format-stats [num]
  (.format (DecimalFormat. "#,##0") num))
