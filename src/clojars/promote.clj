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
  ;; should we add a flag to the DB or check S3 directly?
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

(defonce queue (ArrayBlockingQueue.))

(defn- deploy-to-s3 [file]
  ;; TODO: implement
  )

(defn promote [file]
  (when (empty? (blockers file))
    (deploy-to-s3 file)))

(defn start []
  (.start (Thread. #(promote (.take queue)))))

;; TODO: probably worth periodically queueing all non-promoted
;; releases into here to catch things that fall through the cracks,
;; say if the JVM is restarted before emptying this queue.