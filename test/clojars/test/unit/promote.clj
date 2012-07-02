(ns clojars.test.unit.promote
  (:require [clojure.test :refer :all]
            [clojars.promote :refer :all]
            [clojure.java.io :as io]
            [clojars.maven :as maven]))

(deftest test-snapshot-blockers
  (is (= ["Snapshot versions cannot be promoted"
          "Missing file hooke-1.2.0-SNAPSHOT.jar"
          "Missing file hooke-1.2.0-SNAPSHOT.pom"]
         (blockers (io/resource "hooke-1.2.0-SNAPSHOT.pom")))))

(deftest test-metadata-blockers
  (.mkdirs (.getParentFile (file-for "robert" "hooke" "1.1.2" "pom")))
  (spit (file-for "robert" "hooke" "1.1.2" "pom") "")
  (spit (file-for "robert" "hooke" "1.1.2" "jar") "")
  (is (= ["Missing url"]
         (blockers (io/resource "hooke-1.1.2.pom")))))