(ns clojars.maven
  (:require [clojure.java.io :as io])
  (:import org.apache.maven.model.io.xpp3.MavenXpp3Reader))

(defn model-to-map [model]
  {:name (.getArtifactId model)
   :group (.getGroupId model)
   :version (.getVersion model)
   :description (.getDescription model)
   :homepage (.getUrl model)
   :url (.getUrl model)
   :licenses (.getLicenses model)
   :scm (.getScm model)
   :authors (vec (map #(.getName %) (.getContributors model)))
   :dependencies (vec (mapcat (fn [d] [(keyword (.getGroupId d)
                                               (.getArtifactId d))
                                       (.getVersion d)])
                              (.getDependencies model)))})

(defn read-pom
  "Reads a pom file returning a maven Model object."
  [file]
  (with-open [reader (io/reader file)]
    (.read (MavenXpp3Reader.) reader)))

(def pom-to-map (comp model-to-map read-pom))
