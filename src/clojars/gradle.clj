(ns clojars.gradle
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]))

(defn module-to-map
  "Reads a Gradle module file returning a map"
  [file]
  (with-open [reader (io/reader file)]
    (json/parse-stream reader true)))
