(ns clojars.promote
  (:require [clojars.config :refer [config]]
            [clojars.maven :as maven]
            [clojars.db :as db]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.java.jdbc :as sql]
            [clojure.string :as str]
            [clojure.set :as set]
            [cemerick.pomegranate.aether :as aether]
            [clj-pgp.core :as pgp]
            [clj-pgp.signature :as pgp-sig])
  (:import (java.util.concurrent LinkedBlockingQueue)
           (org.springframework.aws.maven SimpleStorageServiceWagon)
           (java.io File ByteArrayInputStream PrintWriter)
           (org.bouncycastle.openpgp PGPUtil PGPObjectFactory)
           (org.bouncycastle.bcpg ArmoredInputStream)))

(defonce _
  (do (java.security.Security/addProvider
       (org.bouncycastle.jce.provider.BouncyCastleProvider.))
      (aether/register-wagon-factory!
       "s3" (constantly (SimpleStorageServiceWagon.)))))

(defn to-byte-stream [in]
  (let [bs (java.io.ByteArrayOutputStream.)]
    (io/copy in bs)
    bs))

(defn decode-signature [data]
  ; File could be signed with multiple signatures.
  ; In this case it isn't
  (first (pgp/decode-signatures data)))

(defn verify [sig-file data public-key]
  (if public-key
    (let [sig (decode-signature (slurp sig-file)) ]
      (if (= (pgp/key-id sig) (pgp/key-id public-key))
        (pgp-sig/verify data sig public-key)))))

(defn parse-keys [s]
  (try
    (-> s
        .getBytes
        ByteArrayInputStream.
        PGPUtil/getDecoderStream
        PGPObjectFactory.
        .nextObject
        .getPublicKeys
        iterator-seq)
    (catch NullPointerException e)))

(defn file-for [group artifact version extension]
  (let [filename (format "%s-%s.%s" artifact version extension)]
    (apply io/file (concat [(config :repo)] (str/split group #"\.") [artifact version filename]))))

(defn check-file [blockers file]
  (if (.exists file)
    blockers
    (conj blockers (str "Missing file " (.getName file)))))

(defn check-version [version]
  (if (re-find #"-SNAPSHOT$" version)
    "Snapshot versions cannot be promoted"))

(defn check-field [blockers info field pred]
  (if (pred (field info))
    blockers
    (conj blockers (str "Missing " (name field)))))

(defn signed-with? [file sig-file keys]
  (some #(verify sig-file file %) (mapcat parse-keys keys)))

(defn signed? [blockers file keys]
  (let [sig-file (str file ".asc")]
    (if (.exists (io/file sig-file))
      (if (signed-with? file sig-file keys)
        blockers
        (conj blockers (str "Could not verify signature of " file "."
                            " Ensure your public key is in your profile.")))
      (conj blockers (str file " is not signed.")))))

(defn unpromoted? [blockers db {:keys [group name version]}]
  (let [[{:keys [promoted_at]}] (db/promoted? db group name version)]
    (if promoted_at
      (conj blockers "Already promoted.")
      blockers)))

(defn blockers [db {:keys [group name version]}]
  (let [jar (file-for group name version "jar")
        pom (file-for group name version "pom")
        keys (remove nil? (db/group-keys db group))
        info (try (when (.exists pom)
                    (maven/pom-to-map pom))
                  (catch Exception e
                    (.printStackTrace e) {}))]
    (if-let [version-blocker (check-version version)]
      [version-blocker]
      (-> []
        (check-file jar)
        (check-file pom)

        (check-field info :description (complement empty?))
        (check-field info :url #(re-find #"^http" (str %)))
        (check-field info :licenses seq)
        (check-field info :scm identity)

        (signed? jar keys)
        (signed? pom keys)
        (unpromoted? db info)))))

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

(defn promote [db {:keys [group name version] :as info}]
  (println "checking" group "/" name "for promotion...")
  (let [blockers (blockers db info)]
    (if (empty? blockers)
      (if (config :releases-url)
        (do
          (println "Promoting" info)
          (deploy-to-s3 info)
          (db/promote db group name version))
        (println "Didn't promote since :releases-url wasn't set."))
      (do (println "...failed.")
          blockers))))
