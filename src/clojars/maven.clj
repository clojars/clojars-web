(ns clojars.maven
  (:require [clojars.file-utils :as fu]
            [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import (org.apache.maven.artifact.repository.metadata Metadata)
           (org.apache.maven.artifact.repository.metadata.io.xpp3 MetadataXpp3Writer)
           org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader
           (org.apache.maven.model Scm Model License)
           org.apache.maven.model.io.xpp3.MavenXpp3Reader))

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

(defn license-to-map [^License license]
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
     :licenses     (mapv license-to-map (.getLicenses model))
     :scm          (scm-to-map (.getScm model))
     :authors      (mapv #(.getName %) (.getContributors model))
     :packaging    (keyword (.getPackaging model))
     :dependencies (mapv
                     (fn [d] {:group_name (.getGroupId d)
                             :jar_name   (.getArtifactId d)
                             :version    (or (.getVersion d) "")
                             :scope      (or (.getScope d) "compile")})
                     (.getDependencies model))}))

(defn read-pom
  "Reads a pom file returning a maven Model object."
  [file]
  (with-open [reader (io/reader file)]
    (.read (MavenXpp3Reader.) reader)))

(def pom-to-map (comp model-to-map read-pom))

(defn read-metadata
  "Reads a maven-metadata file returning a maven Metadata object."
  ^Metadata [file]
  (with-open [reader (io/reader file)]
    (.read (MetadataXpp3Reader.) reader)))

(defn write-metadata
  "Writes the given metadata out to a file."
  [^Metadata metadata file]
  (with-open [writer (io/writer file)]
    (.write (MetadataXpp3Writer.) writer metadata)))

(defn snapshot-timestamp-version
  [filename]
  (second (re-find #"-(\d{8}\.\d{6}-\d+)\." filename)))

(defn parse-long [^String s]
  (when s
    (Long/parseLong s)))

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
        {:major        (parse-long major)
         :minor        (parse-long minor)
         :incremental  (parse-long incremental)
         :build-number (parse-long build-number)
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

(defn split-qualifier [s]
  (when s
    (when-let [[prefix counter suffix] (seq (rest (re-find #"^([^0-9.-]*?)[.-]?([0-9]*)[.-]?([^0-9.-]*?)$" s)))]
      (let [to-lower (fn [s]
                       (when-not (empty? s) (.toLowerCase s)))]
        [(to-lower (if (and (empty? prefix)
                          (empty? counter))
                      suffix
                      prefix))
         (when-not (empty? counter)
           (parse-long counter))
         (when-not (and (empty? prefix)
                     (empty? counter))
           (to-lower suffix))]))))

(def common-qualifiers
  "common qualifiers in relative sort order"
  ["alpha" "beta" "cr" "rc" "snapshot" "final" "release"])

(defn compare-qualifier-fraction [x y]
  (let [x-value (when (some #{x} common-qualifiers) (.indexOf common-qualifiers x))
        y-value (when (some #{y} common-qualifiers) (.indexOf common-qualifiers y))]
    (cond
      (not (or x-value y-value))   0 ; neither are common. no winner
      (and x-value (not y-value)) -1 ; x is known, but y isn't. x wins
      (and y-value (not x-value))  1 ; y is known, but x isn't. y wins
      (< -1 x-value y-value)      -1 ; both fractions are common, x has a lower sort order
      (< -1 y-value x-value)       1 ; both fractions are common, y has a lower sort order
      :else                        0)))

(defn compare-qualifiers [qx qy]
  (let [[qx-prefix qx-counter qx-suffix] (split-qualifier qx)
        [qy-prefix qy-counter qy-suffix] (split-qualifier qy)
        qx-counter (or qx-counter -1)
        qy-counter (or qy-counter -1)]
    (if qx
      (if qy
        (numeric-or
          (compare-qualifier-fraction qx-prefix qy-prefix)
          (compare qx-counter qy-counter)
          (compare-qualifier-fraction qx-suffix qy-suffix)
          (cond
            (and (> (count qx) (count qy)) (.startsWith qx qy)) -1 ; x is longer, it's older
            (and (< (count qx) (count qy)) (.startsWith qy qx))  1 ; y is longer, it's older
            :else (compare qx qy)))                                ; same length, so string compare
        -1)                       ; y has no qualifier, it's younger
      (if qy
        1                         ; x has no qualifier, it's younger
        0)))                      ; no qualifiers
  )

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
      (compare-qualifiers (:qualifier x) (:qualifier y))
      (compare (:build-number x 0) (:build-number y 0)))))

(defn snapshot-version? [version]
  (.endsWith version "-SNAPSHOT"))

(defn central-metadata
  "Read the metadata from maven central for the given artifact."
  [group name]
  (try
    (read-metadata
      (format "https://repo1.maven.org/maven2/%s/%s/maven-metadata.xml" (fu/group->path group) name))
    (catch java.io.FileNotFoundException _)))

(defn exists-on-central?*
  "Checks if any versions of the given artifact exist on central.
   Makes at least one network call on every invocation."
  [group-id artifact-id]
  (loop [attempt 0]
    (let [ret (try
                (boolean (central-metadata group-id artifact-id))
                (catch Exception _ :failure))]
      (if (and
            (= ret :failure)
            (< attempt 9))
        (do
          (Thread/sleep (bit-shift-left 1 (inc attempt)))
          (recur (inc attempt)))
        ret))))

(def exists-on-central?
  "Checks if any versions of the given artifact exist on central.
   Memoized version of exists-on-central?*."
  (memoize exists-on-central?*))

(def shadow-allowlist
  (delay (-> "shadow-allowlist.edn" io/resource slurp edn/read-string)))

(defn can-shadow-maven? [group-id artifact-id]
  (contains? @shadow-allowlist
    (symbol (format "%s/%s" group-id artifact-id))))

