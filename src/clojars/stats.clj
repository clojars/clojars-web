(ns clojars.stats
  (:require [clojure.core.memoize :as memo]
            [com.stuartsierra.component :as component])
  (:import java.nio.file.Files
           java.nio.file.LinkOption))

(defprotocol Stats
  (download-count
    [t group-id artifact-id]
    [t group-id artifact-id version])
  (total-downloads [t]))

(defrecord MapStats [s]
  Stats
  (download-count [t group-id artifact-id]
     (->> (s [group-id artifact-id])
          vals
          (apply +)))
  (download-count [t group-id artifact-id version]
    (get-in s [[group-id artifact-id] version] 0))
  (total-downloads [t]
    (apply + (mapcat vals (vals s)))))

(defn all* [path]
  (->MapStats (if (Files/exists path (make-array LinkOption 0))
                (read (java.io.PushbackReader. (Files/newBufferedReader path)))
                {})))

(def all (memo/ttl all* :ttl/threshold (* 60 60 1000))) ;; 1 hour

(defrecord FileStats [fs-factory path-factory fs path]
  Stats
  (download-count [t group-id artifact-id]
    (download-count (all path) group-id artifact-id))
  (download-count [t group-id artifact-id version-id]
    (download-count (all path) group-id artifact-id version-id))
  (total-downloads [t]
    (total-downloads (all path)))
  component/Lifecycle
  (start [t]
    (if fs
      t
      (let [fs (fs-factory)]
        (assoc t
               :fs fs
               :path (path-factory fs)))))
  (stop [t]
    (when fs
      (try
        (.close fs)
        (catch UnsupportedOperationException _
          ;; java says we should close these, but then defines
          ;; closing the default one to be an error :/
          )))
    (assoc t
           :fs nil
           :path nil)))

(defn file-stats [stats-dir]
  (map->FileStats {:path-factory #(.getPath %
                                            (str stats-dir "/all.edn")
                                            (make-array String 0))}))
