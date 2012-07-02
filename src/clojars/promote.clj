(ns clojars.promote
  (:require [clojars.config :refer [config]]
            [clojars.maven :as maven]
            [clojure.java.io :as io])
  (:import (java.util.concurrent ArrayBlockingQueue)))

(defn file-for [group artifact version extension]
  (let [filename (format "%s-%s.%s" artifact version extension)]
    (io/file (config :repo) group artifact version filename)))

(defn check-file [blockers file]
  (if (.exists file)
    blockers
    (conj blockers (str "Missing file " (.getName file)))))

(defn check-version [blockers version]
  (if (re-find #"-SNAPSHOT$" version)
    (conj blockers "Snapshot versions cannot be promoted")
    blockers))

(defn check-field [blockers info field]
  (if (field info)
    blockers
    (conj blockers (str "Missing " (name field)))))

(defn signed? [blockers file]
  ;; TODO: implement
  blockers)

(defn unpromoted? [blockers info]
  ;; TODO implement
  blockers)

(defn blockers [file]
  (let [{:keys [group name version] :as info} (maven/pom-to-map file)
        jar (file-for group name version "jar")
        pom (file-for group name version "pom")]
    (-> []
        (check-version (:version info))
        (check-file jar)
        (check-file pom)

        (check-field info :description)
        (check-field info :url)
        (check-field info :licenses)
        (check-field info :scm)

        (signed? jar)
        (signed? pom)
        (unpromoted? info))))
