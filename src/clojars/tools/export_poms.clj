(ns clojars.tools.export-poms
  "This tool exports all of the jars in the database to a pseudo-repo that
  the maven index (see the maven-index-repo script). The indexer just needs
  a minimal pom, with GAV, description, and packaging. 

  We export these from the db instead of pulling them down from S3, as the
  latter is expensive, as it requires listing the full repo on each sync."
  (:gen-class)
  (:require
   [clojars.config :refer [config]]
   [clojars.db :as db]
   [clojars.file-utils :as fu]
   [clojars.maven :as maven]
   [clojure.data.xml :as xml]
   [clojure.java.io :as io])
  (:import
   (java.util
    Date)))

(set! *warn-on-reflection* true)

(defn- gen-pom-tags
  [{:keys [description group_name jar_name packaging version]}]
  (xml/sexp-as-element
   [:project {:xmlns "http://maven.apache.org/POM/4.0.0"
              :xmlns:xsi "http://www.w3.org/2001/XMLSchema-instance"
              :xsi:schemaLocation "http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd"}
    [:modelVersion "4.0.0"]
    [:packaging (or packaging "jar")]
    [:groupId group_name]
    [:artifactId jar_name]
    [:version version]
    [:name jar_name]
    [:description description]]))

(defn- gen-pom-file-path
  [repo-dir {:as _jar-row :keys [group_name jar_name version]}]
  (format "%s/%s/%s/%s/%s-%s.pom"
          repo-dir
          (fu/group->path group_name)
          jar_name
          version
          jar_name
          version))

(defn- process-jar
  [repo-dir {:as jar-row :keys [created version]}]
  (let [pom-file (io/file (gen-pom-file-path repo-dir jar-row))]
    ;; The jar entries are static, so we only need to write them once. However,
    ;; a later SNAPSHOT of the same version may update the description, so
    ;; we do overwrite SNAPSHOT poms to get the latest.
    (when (or (maven/snapshot-version? version)
              (not (.exists pom-file)))
      (.mkdirs (.getParentFile pom-file))
      (with-open [w (io/writer pom-file)]
        (xml/emit (gen-pom-tags jar-row) w))
      ;; The indexer stores the last-modified time of the jar as version
      ;; creation time in the index
      (.setLastModified pom-file (.getTime ^Date created)))))

(defn export-all-poms
  [db repo-dir]
  (doseq [jar (db/all-jars db)]
    (process-jar repo-dir jar)))

(defn -main
  [repo-dir]
  (let [{:keys [db]} (config :production)]
    (export-all-poms db repo-dir)))
