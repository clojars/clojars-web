(ns clojars.maven
  (:require [clojure.java.io :as io]
            [clojars.config :refer [config]]
            [clojure.string :refer [split]]
            [clojars.errors :refer [report-error]])
  (:import org.apache.maven.model.io.xpp3.MavenXpp3Reader
           org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader
           java.io.IOException
           (org.apache.maven.model Scm Model License)
           (org.apache.maven.artifact.repository.metadata Metadata)
           (org.apache.maven.artifact.repository.metadata.io.xpp3 MetadataXpp3Writer)))

(defn without-nil-values
  "Prunes a map of pairs that have nil values."
  [m]
  (reduce (fn [m entry]
            (if (nil? (val entry))
              m
              (conj m entry)))
          (empty m) m))

(defn scm-to-map [^Scm scm]
  (when scm
    (without-nil-values
      {:connection           (.getConnection scm)
       :developer-connection (.getDeveloperConnection scm)
       :tag                  (.getTag scm)
       :url                  (.getUrl scm)})))

(defn license-to-seq [^License license]
  (without-nil-values
    {:name         (.getName license)
     :url          (.getUrl license)
     :distribution (.getDistribution license)
     :comments     (.getComments license)}))

(defn model-to-map [^Model model]
  (without-nil-values
    {:name         (or (.getArtifactId model)
                       (-> model .getParent .getArtifactId))
     :group        (or (.getGroupId model)
                       (-> model .getParent .getGroupId))
     :version      (or (.getVersion model)
                       (-> model .getParent .getVersion))
     :description  (.getDescription model)
     :homepage     (.getUrl model)
     :url          (.getUrl model)
     :licenses     (mapv license-to-seq (.getLicenses model))
     :scm          (scm-to-map (.getScm model))
     :authors      (mapv #(.getName %) (.getContributors model))
     :packaging    (keyword (.getPackaging model))
     :dependencies (mapv
                     (fn [d] {:group_name (.getGroupId d)
                              :jar_name   (.getArtifactId d)
                              :version    (.getVersion d)
                              :scope      (or (.getScope d) "compile")})
                     (.getDependencies model))}))

(defn read-pom
  "Reads a pom file returning a maven Model object."
  [file]
  (with-open [reader (io/reader file)]
    (.read (MavenXpp3Reader.) reader)))

(def pom-to-map (comp model-to-map read-pom))

(defn ^Metadata read-metadata
  "Reads a maven-metadata file returning a maven Metadata object."
  [file]
  (with-open [reader (io/reader file)]
    (.read (MetadataXpp3Reader.) reader)))

(defn write-metadata
  "Writes the given metadata out to a file."
  [^Metadata metadata file]
  (with-open [writer (io/writer file)]
    (.write (MetadataXpp3Writer.) writer metadata)))

(defn snapshot-version
  "Get snapshot version from maven-metadata.xml used in pom filename"
  [file]
  (let [versioning (-> (read-metadata file) .getVersioning .getSnapshot)]
    (str (.getTimestamp versioning) "-" (.getBuildNumber versioning))))

(defn directory-for
  "Directory for a jar under repo"
  [{:keys [group_name jar_name version]}]
  (apply io/file (concat [(config :repo)] (split group_name #"\.") [jar_name version])))

(defn snapshot-pom-file [{:keys [jar_name version] :as jar}]
  (let [metadata-file (io/file (directory-for jar) "maven-metadata.xml")
        snapshot (snapshot-version metadata-file)
        filename (format "%s-%s-%s.pom" jar_name (re-find #"\S+(?=-SNAPSHOT$)" version) snapshot)]
    (io/file (directory-for jar) filename)))

(defn jar-to-pom-map [reporter {:keys [jar_name version] :as jar}]
  (try
    (let [pom-file (if (re-find #"SNAPSHOT$" version)
                     (snapshot-pom-file jar)
                     (io/file (directory-for jar) (format "%s-%s.%s" jar_name version "pom")))]
      (pom-to-map (str pom-file)))
    (catch IOException e
      (report-error reporter (ex-info "Failed to create pom map" jar e))
      nil)))

(defn github-info [pom-map]
  (let [url (get-in pom-map [:scm :url])
        github-re #"^https?://github.com/([^/]+/[^/]+)"
        user-repo (->> (str url) (re-find github-re) second)]
    user-repo))

(defn commit-url [pom-map]
  (let [{:keys [url tag]} (:scm pom-map)
        base-url (re-find #"https?://github.com/[^/]+/[^/]+" (str url))]
    (if (and base-url tag) (str base-url "/commit/" tag))))

(defn parse-int [^String s]
  (when s
    (Integer/parseInt s)))

(defn parse-version
  "Parse a Maven-style version number.

  The basic format is major[.minor[.increment]][(-|.)(buildNumber|qualifier)]

  The major, minor, increment and buildNumber are numeric with leading zeros
  disallowed (except plain 0).  If the value after the first - is non-numeric
  then it is assumed to be a qualifier.  If the format does not match then we
  just treat the whole thing as a qualifier."
  [s]
  (let [[match major minor incremental _ _ build-number qualifier]
        (re-matches #"(0|[1-9][0-9]*)(?:\.(0|[1-9][0-9]*)(?:\.(0|[1-9][0-9]*))?)?(?:(-|\.)((0|[1-9][0-9]*)|(.*)))?" s)]
    (try
      (without-nil-values
        {:major        (parse-int major)
         :minor        (parse-int minor)
         :incremental  (parse-int incremental)
         :build-number (parse-int build-number)
         :qualifier    (if match qualifier s)})
      (catch NumberFormatException _
        {:qualifier s}))))

(defmacro numeric-or
  "Evaluates exprs one at a time.  Returns the first that returns non-zero."
  ([x] x)
  ([x & exprs]
   `(let [value# ~x]
      (if (zero? value#)
        (numeric-or ~@exprs)
        value#))))

(defn compare-versions
  "Compare two maven versions.  Accepts either the string or parsed
  representation."
  [x y]
  (let [x (if (string? x) (parse-version x) x)
        y (if (string? y) (parse-version y) y)]
    (numeric-or
      (compare (:major x 0) (:major y 0))
      (compare (:minor x 0) (:minor y 0))
      (compare (:incremental x 0) (:incremental y 0))
      (let [qx (:qualifier x)
            qy (:qualifier y)]
        (if qx
          (if qy
            (if (and (> (count qx) (count qy)) (.startsWith qx qy))
              -1                      ; x is longer, it's older
              (if (and (< (count qx) (count qy)) (.startsWith qx qy))
                1                     ; y is longer, it's older
                (compare qx qy)))     ; same length, so string compare
            -1)                       ; y has no qualifier, it's younger
          (if qy
            1                         ; x has no qualifier, it's younger
            0)))                      ; no qualifiers
      (compare (:build-number x 0) (:build-number y 0)))))

