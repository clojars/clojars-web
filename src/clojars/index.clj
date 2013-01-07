(ns clojars.index
  (:require [clojars.config :refer [config]]
            [clojars.event :as ev]
            [clojure.java.io :as io]
            [clucy.core :as clucy]))

(defn event-log-file [type]
  (io/file (config :event-dir) (str (name type) ".clj")))

(defn find-user [username])

(defn group-members [group])

(defn search [query])

(defn recently-pushed [])

(defn index-event-file [event-file index-file]
  (with-open [index (clucy/disk-index index-file)
              rdr (io/reader event-file)]
    (clucy/add index (map read-string (line-seq rdr)))))

(defn -main []
  (index-event-file (event-log-file :membership)
                    (io/file (config :index-dir) "membership")))
