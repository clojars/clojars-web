(ns clojars.stats
  (:require [clojars.config :as config]))

(defn all []
  (read (java.io.PushbackReader. (java.io.FileReader.
                                  (str (config/config :stats-dir)
                                       "all.edn")))))

(defn download-count [group-id artifact-id & [version]]
  (let [ds ((all) [group-id artifact-id])]
    (if version
      (ds version)
      (->> ds
           (map second)
           (apply +)))))