(ns clojars.maven
  (:require [clojure.contrib.duck-streams :as ds])
  (:use [clojure.contrib.condition :only [raise]])
  (:import (org.apache.maven.model Model
                                   Dependency
                                   Contributor)
           (org.apache.maven.model.io.xpp3 MavenXpp3Writer)
           org.apache.maven.artifact.repository.ArtifactRepositoryFactory
           org.apache.maven.artifact.repository.layout.ArtifactRepositoryLayout
           org.apache.maven.artifact.factory.ArtifactFactory
           org.apache.maven.project.artifact.ProjectArtifactMetadata
           org.apache.maven.artifact.deployer.ArtifactDeployer
           org.apache.maven.settings.Settings
           org.apache.maven.settings.MavenSettingsBuilder
           org.codehaus.plexus.embed.Embedder
           (java.io PushbackReader
                    StringWriter
                    File
                    FileWriter)))

;; weirdo magic plexus container stuff to make maven work
;; TODO: find out if it's safe to just leave these hanging around like
;; this 
(def embedder (doto (Embedder.) (.start)))
(def container (.getContainer embedder))

(defn read-vec
  "Produces a vector of clojure data structures read from file."
  [file]
  (with-open [rdr (PushbackReader. (ds/reader "/tmp/jarspec.clj"))]
   (loop [v []]
     (let [x (read rdr false ::end)]
       (if (not= x ::end)
         (recur (conj v x))
         v)))))

(defn defjar? [x]
  (and (list? x)
       (= (first x) 'defjar)))

(defn defjar-to-map [[dj name version & options]]
  (when-not name
    (raise :type ::invalid-defjar :missing :name
           :message "defjar requires a name"))
  (when-not version
    (raise :type ::invalid-defjar :missing :version
           :message "defjar requires a version"))
  (when-not (even? (count options))
    (raise :type ::invalid-defjar :invalid :options
           :message "defjar requires an even number of options"))
  (into (apply hash-map options) 
        {:name name,
         :version version}))

(defn make-dependency
  "Constructs a Maven Dependency object.  The jarsym should be
  groupId/artifactId."
  [jarsym version]
  (doto (Dependency.)
    (.setGroupId (namespace jarsym))
    (.setArtifactId (name jarsym))
    (.setVersion version))) 

(defn make-contributor
  [name]
  (doto (Contributor.)
    (.setName name)))

(defn make-model
  "Produces a maven Model from a defjar map."
  [dj]
  (doto (Model.)
    (.setGroupId (or (namespace (:name dj)) "org.clojars.ato"))
    (.setArtifactId (name (:name dj)))
    (.setVersion (:version dj))

    (.setDescription (:description dj))
    (.setUrl (:homepage dj))
    (.setContributors (doall (map make-contributor (:authors dj))))

    (.setPackaging "jar")

    (.setDependencies (doall (map #(apply make-dependency %)
                                  (partition 2 (:dependencies dj)))))))

(defn model-to-xml 
  "Converts a maven model to a string of XML."
  [model]
  (let [sw (StringWriter.)]
    (.write (MavenXpp3Writer.) sw model)
    (str sw)))

(defn model-to-file
  "Converts a maven model to a temporary file (containing XML)."
  [model]
  (let [f (File/createTempFile "mvninstall" ".pom")]
    (.deleteOnExit f)
    (with-open [writer (FileWriter. f)]
      (.write (MavenXpp3Writer.) writer model))
    f))

(defn make-repo
  "Does the crazy factory voodoo necessary to get an ArtifactRepository object."
  [id url]
  (-> (.lookup container ArtifactRepositoryFactory/ROLE)
      (.createDeploymentArtifactRepository 
       id url (.lookup container ArtifactRepositoryLayout/ROLE "default") true)))

(defn make-settings
  "Makes a maven Settings object"
  []
  (.buildSettings (.lookup container MavenSettingsBuilder/ROLE)))

(defn make-local-repo
  "Returns the local maven repository."
  []
  (let [path (.getLocalRepository (make-settings))
        url (if (.startsWith path "file:") path (str "file://" path))]
    (make-repo "local" url)))

(defn make-artifact
  "Makes a maven artifact from a maven model (more crazy voodoo)."
  [m]
  (let [artifact (.createArtifactWithClassifier
                  (.lookup container ArtifactFactory/ROLE)
                  (.getGroupId m)
                  (.getArtifactId m)
                  (.getVersion m)
                  (.getPackaging m)
                  nil)]
    (.addMetadata artifact (ProjectArtifactMetadata. 
                            artifact (model-to-file m)))
    artifact))

(defn deploy-model [jarfile model repo-path]
  (.deploy 
   (.lookup container ArtifactDeployer/ROLE)
   jarfile (make-artifact model) (make-repo "clojars" repo-path)
   (make-local-repo)))

(comment
 (.getFile (first (.getMetadataList (make-artifact model))))

 (deploy-model (File. "/tmp/foo.jar") model "file:///tmp")

 (def model (make-model (defjar-to-map (first (filter defjar? (read-vec
                                                               "/tmp/jarspec.clj"))))))Z
 (model-to-xml model))
