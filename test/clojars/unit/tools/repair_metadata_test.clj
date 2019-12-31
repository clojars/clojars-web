(ns clojars.unit.tools.repair-metadata-test
  (:require [clojars.file-utils :as futil]
            [clojars.maven :as mvn]
            [clojars.tools.repair-metadata :as rmd]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            digest)
  (:import (org.apache.commons.io FileUtils)))

(def ^:dynamic *tmp-repo* nil)

(use-fixtures :each
              (fn [f]
                (binding [*tmp-repo* (doto (io/file (FileUtils/getTempDirectory)
                                                    (str "bad-metadata" (System/currentTimeMillis)))
                                       .mkdirs)]
                  (FileUtils/copyDirectory (io/file (io/resource "bad-metadata")) *tmp-repo*)
                  (try
                    (f)
                    (finally
                      (FileUtils/deleteDirectory *tmp-repo*))))))

(defn metadata-for-artifact [mds artifact-id]
  (first (filter #(= artifact-id (:artifact-id %)) mds)))

(deftest find-bad-metadata-does-the-right-thing
  (let [mds (rmd/find-bad-metadata *tmp-repo*)
        bar (metadata-for-artifact mds "bar")
        baz (metadata-for-artifact mds "baz")]
    (is (= 2 (count mds)))
    (is bar)
    (is (:missing-versions? bar))
    (is (:invalid-sums? bar))
    (is baz)
    (is (not (:missing-versions? baz)))
    (is (:invalid-sums? baz))
    (is (nil? (metadata-for-artifact mds "biscuit")))))

(deftest repair-metadata-corrects-versions
  (let [backup-dir (doto (io/file (FileUtils/getTempDirectory)
                                  (str "bad-metadata-backup" (System/currentTimeMillis)))
                     .mkdirs)
        bar-file (io/file *tmp-repo* "foo/bar/maven-metadata.xml")
        versions ["0.1.0" "0.2.0" "0.4.0" "0.5.0-SNAPSHOT"]]
    (try
      (doseq [v versions :let [n (atom (System/currentTimeMillis))]]
        (.setLastModified (io/file *tmp-repo* "foo/bar" v) (swap! n inc)))
      (rmd/repair-metadata backup-dir (metadata-for-artifact (rmd/find-bad-metadata *tmp-repo*) "bar"))
      (testing "makes a backup"
        (is (= 1 (count (filter #(= "maven-metadata.xml" (.getName %)) (file-seq backup-dir))))))

      (testing "creates the correct metadata"
        (let [md (mvn/read-metadata bar-file)
              versioning (.getVersioning md)]
          (is (= "0.4.0" (.getRelease versioning)))
          (is (= versions (.getVersions versioning)))))

      (testing "writes correct sums"
        (is (futil/valid-checksum-file? bar-file :md5))
        (is (futil/valid-checksum-file? bar-file :sha1)))

      (finally
        (FileUtils/deleteDirectory backup-dir)))))

(deftest repair-metadata-corrects-sums
  (let [backup-dir (doto (io/file (FileUtils/getTempDirectory)
                                  (str "bad-metadata-backup" (System/currentTimeMillis)))
                     .mkdirs)
        baz-file (io/file *tmp-repo* "foo/baz/maven-metadata.xml")]
    (try
      (rmd/repair-metadata backup-dir (metadata-for-artifact (rmd/find-bad-metadata *tmp-repo*) "baz"))
      (testing "makes a backup"
        (is (= 1 (count (filter #(= "maven-metadata.xml" (.getName %)) (file-seq backup-dir))))))

      (testing "writes correct sums"
        (is (futil/valid-checksum-file? baz-file :md5))
        (is (futil/valid-checksum-file? baz-file :sha1)))

      (finally
        (FileUtils/deleteDirectory backup-dir)))))

