(ns clojars.gradle
  (:require
   [cheshire.core :as json]
   [clojars.maven :as maven]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(defn range-version?
  [v]
  ;; See https://docs.gradle.org/current/userguide/dependency_versions.html
  (or (maven/range-version? v)
      (= "+" v)
      (str/ends-with? v ".+")))

;; See https://docs.gradle.org/current/userguide/dependency_versions.html
(let [dynamic-latest-versions #{"latest.integration" "latest.release"}]
  (defn latest-metaversion-version?
    [v]
    (contains? dynamic-latest-versions v)))

;; See https://github.com/gradle/gradle/blob/master/platforms/documentation/docs/src/docs/design/gradle-module-metadata-latest-specification.md#version-value 
(defn dependency-versions
  [module]
  (reduce
   (fn [acc variant]
     (into acc (comp
                (map
                 (fn [{:keys [version]}]
                   (or (:strictly version)
                       (:requires version)
                       (:prefers version))))
                (remove nil?))
           (:dependencies variant)))
   []
   (:variants module)))

(defn module-to-map
  "Reads a Gradle module file returning a map"
  [file]
  (with-open [reader (io/reader file)]
    (json/parse-stream reader true)))
