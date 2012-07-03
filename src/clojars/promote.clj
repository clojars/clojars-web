(ns clojars.promote
  (:require [clojars.config :refer [config]]
            [clojars.maven :as maven]
            [clojure.java.io :as io]
            [cemerick.pomegranate.aether :as aether])
  (:import (java.util.concurrent LinkedBlockingQueue)
           (org.springframework.aws.maven SimpleStorageServiceWagon)))

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
  ;; TODO: check DB, needs migration
  blockers)

(defn blockers [{:keys [group name version]}]
  (let [jar (file-for group name version "jar")
        pom (file-for group name version "pom")
        info (try (maven/pom-to-map pom)
                  (catch Exception _ {}))]
    (-> []
        (check-version version)
        (check-file jar)
        (check-file pom)

        (check-field info :description)
        (check-field info :url)
        (check-field info :licenses)
        (check-field info :scm)

        (signed? jar)
        (signed? pom)
        (unpromoted? info))))

(def releases {:url "s3://clojars/releases/"
               :username (config :releases-access-key)
               :passphrase (config :releases-secret-key)})

(aether/register-wagon-factory! "s3" (constantly (SimpleStorageServiceWagon.)))

(defn- add-coords [{:keys [group name version classifier] :as info}
                   files extension]
  ;; TODO: classifier?
  (assoc files [(symbol group name) version :extension extension]
         (file-for group name version extension)))

(defn- deploy-to-s3 [info]
  (let [files (reduce (partial add-coords info) {}
                      ["jar" "jar.asc" "pom" "pom.asc"])]
    (aether/deploy-artifacts :artifacts (keys files)
                             :files files
                             :transfer-listener :stdout
                             :repository {"releases" releases})))

(defn promote [info]
  (when (empty? (blockers info))
    (deploy-to-s3 info)))

(defonce queue (LinkedBlockingQueue.))

(defn start []
  (.start (Thread. #(promote (.take queue)))))

;; TODO: probably worth periodically queueing all non-promoted
;; releases into here to catch things that fall through the cracks,
;; say if the JVM is restarted before emptying this queue.
