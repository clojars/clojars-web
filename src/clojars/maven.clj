(ns clojars.maven
  (:require [clojure.java.io :as io])
  (:import org.apache.maven.model.io.xpp3.MavenXpp3Reader))

(defn model-to-map [model]
  {:name (.getArtifactId model)
   :group (.getGroupId model)
   :version (.getVersion model)
   :description (.getDescription model)
   :homepage (.getUrl model)
   :authors (vec (map #(.getName %) (.getContributors model)))
   ;; TODO: doesn't appear to be used anywhere?
   :dependencies (vec (mapcat (fn [d] [(symbol (.getGroupId d)
                                               (.getArtifactId d))
                                       (.getVersion d)])
                              (.getDependencies model)))})

(defn read-pom
  "Reads a pom file returning a maven Model object."
  [file]
  (with-open [reader (io/reader file)]
    (.read (MavenXpp3Reader.) reader)))
