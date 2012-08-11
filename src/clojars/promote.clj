(ns clojars.promote
  (:require [clojars.config :refer [config]]
            [clojars.maven :as maven]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [cemerick.pomegranate.aether :as aether]
            [clojars.db :as db]
            [clojure.java.jdbc :as sql]
            [korma.core :refer [select fields where update set-fields]])
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

(declare check-signature)

(defn- fetch-key [signature err]
  (if (re-find #"Can't check signature: public key not found" err)
    (let [key (second (re-find #"using \w+ key ID (.+)" err))
          {:keys [exit]} (sh/sh "gpg" "--recv-keys" key)]
      (if (zero? exit)
        (check-signature signature)))))

(defn- check-signature [signature]
  (let [err (java.io.StringWriter.)
        out (java.io.StringWriter.)
        {:keys [exit]} (binding [*err* (java.io.PrintWriter. err), *out* out]
                         (sh/sh "gpg" "--verify" (str signature)))]
    (or (zero? exit)
        (fetch-key signature (str err)))))

(defn signed? [blockers file]
  (if (check-signature (str file ".asc"))
    blockers
    (conj blockers (str file " is not signed."))))

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
        info (try (maven/pom-to-map pom)
                  (catch Exception _ {}))]
    ;; TODO: convert this to a lazy seq for cheaper qualification checks
    (-> []
        (check-version version)
        (check-file jar)
        (check-file pom)

        ;; TODO: check contents, not just presence
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

(defn promote [{:keys [group name version] :as info}]
  (sql/with-connection (config :db)
    (sql/transaction
     (let [blockers (blockers info)]
       (if (empty? blockers)
         (do
           (println "Promoting" info)
           (update db/jars
                   (set-fields {:promoted_at (java.util.Date.)})
                   (where {:group_name group :jar_name name :version version}))
           (deploy-to-s3 info))
         blockers)))))

(defonce queue (LinkedBlockingQueue.))

(defn start []
  (.start (Thread. #(loop []
                      (try (promote (.take queue))
                           (catch Exception e
                             (.printStackTrace e)))
                      (recur)))))

;; TODO: probably worth periodically queueing all non-promoted
;; releases into here to catch things that fall through the cracks,
;; say if the JVM is restarted before emptying this queue.
