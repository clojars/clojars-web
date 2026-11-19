(ns clojars.credential-scan
  "Finds credentials that were accidentally packaged into a deployed artifact.
  Most often this is a deploy token or password in the project.clj that
  Leiningen copies into every jar."
  (:require
   [clojure.java.io :as io])
  (:import
   (java.io
    File)
   (java.util.zip
    ZipEntry
    ZipException
    ZipFile)))

(def ^:private build-file-names
  #{"bb.edn" "build.boot" "deps.edn" "pom.xml" "profiles.clj" "project.clj" "settings.xml"})

;; Limit how many bytes we scan, to avoid DoS from hostile JARs.
(def ^:private max-entry-bytes (* 1024 1024))

(def ^:private deploy-token-re #"CLOJARS_[0-9a-f]{60}")

(def ^:private password-res
  [#":password\s+\"[^\"]+\""
   #"<password>\s*(?!\$\{)[^<\s][^<]*</password>"])

(defn find-credential
  "Returns `{:credential-type}` for the credential found in `text`, or nil.
  A deploy token finding takes precedence, and its `:tokens` holds every token
  in `text` so they can be revoked."
  [text]
  (if-some [tokens (seq (re-seq deploy-token-re text))]
    {:credential-type :deploy-token
     :tokens          (set tokens)}
    (when (some #(re-find % text) password-res)
      {:credential-type :password})))

(defn- build-file-entry?
  [^ZipEntry entry]
  (and (not (.isDirectory entry))
       ;; .getSize returns the size provided by the jar's own metadata, which may not match what
       ;; the entry decompresses to. We use read-entry to handle malicious/malformed JARs.
       (<= (.getSize entry) max-entry-bytes)
       (contains? build-file-names (.getName (io/file (.getName entry))))))

(defn- read-entry
  [^ZipFile zip ^ZipEntry entry]
  (with-open [in (.getInputStream zip entry)]
    (String. (.readNBytes in (int max-entry-bytes)) "UTF-8")))

(defn- scan-jar
  [^File f]
  (try
    (with-open [zip (ZipFile. f)]
      (some (fn [^ZipEntry entry]
              (some-> (find-credential (read-entry zip entry))
                      (assoc :entry (.getName entry))))
            (filter build-file-entry? (enumeration-seq (.entries zip)))))
    (catch ZipException _
      nil)))

(defn scan-artifact
  "Returns the `find-credential` result for the first credential found in the
  pom `f`, or in a build file inside the jar `f` (`:entry` names it), or nil.
  Other artifact types aren't scanned."
  [^File f]
  (let [file-name (.getName f)]
    (cond
      (.endsWith file-name ".jar")
      (scan-jar f)

      (and (.endsWith file-name ".pom")
           (<= (.length f) max-entry-bytes))
      (find-credential (slurp f)))))
