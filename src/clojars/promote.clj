(ns clojars.promote
  (:require [clojars.config :refer [config]]
            [clojars.maven :as maven]
            [clojars.db :as db]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.java.jdbc :as sql]
            [clojure.string :as str]
            [clojure.set :as set]
            [korma.db :as korma]
            [cemerick.pomegranate.aether :as aether]
            [korma.core :refer [select fields where update set-fields]])
  (:import (java.util.concurrent LinkedBlockingQueue)
           (org.springframework.aws.maven SimpleStorageServiceWagon)
           (java.io File ByteArrayInputStream PrintWriter)))

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

(defn check-field [blockers info field pred]
  (if (pred (field info))
    blockers
    (conj blockers (str "Missing " (name field)))))

;; if you think this looks crazy, you should see what it looked like
;; with bouncy castle.
(defn signed-with? [file sig-file keys]
  (let [temp-home (str (doto (File/createTempFile "clojars" "gpg")
                         .delete .mkdirs (.setReadable true true)))]
    (sh/sh "gpg" "--homedir" temp-home "--import" :in (str/join "\n" keys))
    (let [{:keys [exit out err]} (sh/sh "gpg" "--homedir" temp-home
                                        "--verify" (str sig-file) (str file))]
      (doseq [f (reverse (file-seq (io/file temp-home)))] (.delete f))
      (or (zero? exit) (println "GPG error:" out "\n" err)))))

(defn signed? [blockers file keys]
  (let [sig-file (str file ".asc")]
    (if (and (.exists (io/file sig-file))
             (signed-with? file sig-file keys))
      blockers
      (conj blockers (str file " is not signed.")))))

(defn unpromoted? [blockers {:keys [group name version]}]
  (let [[{:keys [promoted_at]}] (select db/jars (fields :promoted_at)
                                        (where {:group_name group
                                                :jar_name name
                                                :version version}))]
    (if promoted_at
      (conj blockers "Already promoted.")
      blockers)))

(defn blockers [{:keys [group name version]}]
  (let [jar (file-for group name version "jar")
        pom (file-for group name version "pom")
        keys (db/group-keys group)
        info (try (if (.exists pom)
                    (maven/pom-to-map pom))
                  (catch Exception e
                    (.printStackTrace e) {}))]
    (-> []
        (check-version version)
        (check-file jar)
        (check-file pom)

        (check-field info :description (complement empty?))
        (check-field info :url #(re-find #"^http" (str %)))
        (check-field info :licenses seq)
        (check-field info :scm identity)

        (signed? jar keys)
        (signed? pom keys)
        (unpromoted? info))))

(defonce _
  (aether/register-wagon-factory!
   "s3" (constantly (SimpleStorageServiceWagon.))))

(defn- add-coords [{:keys [group name version classifier] :as info}
                   files extension]
  ;; TODO: classifier?
  (assoc files [(symbol group name) version :extension extension]
         (file-for group name version extension)))

(defn- deploy-to-s3 [info]
  (let [files (reduce (partial add-coords info) {}
                      ["jar" "jar.asc" "pom" "pom.asc"])
        releases-repo {:url (config :releases-url)
                       :username (config :releases-access-key)
                       :passphrase (config :releases-secret-key)}]
    (aether/deploy-artifacts :artifacts (keys files)
                             :files files
                             :transfer-listener :stdout
                             :repository {"releases" releases-repo})))

(defn promote [{:keys [group name version] :as info}]
  (korma/transaction
   (println "checking" group "/" name "for promotion...")
   (let [blockers (blockers info)]
     (if (empty? blockers)
       (when (config :releases-url)
         (println "Promoting" info)
         (deploy-to-s3 info)
         ;; TODO: this doesn't seem to be happening. db locked?
         (update db/jars
                 (set-fields {:promoted_at (java.util.Date.)})
                 (where {:group_name group :jar_name name :version version})))
       (do (println "...failed.")
           blockers)))))

(defonce queue (LinkedBlockingQueue.))

(defn start []
  (.start (Thread. #(loop []
                      (locking #'promote
                        (try (promote (.take queue))
                             (catch Exception e
                               (.printStackTrace e))))
                      (recur)))))
