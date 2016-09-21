(ns clojars.test.unit.tools.upload-repo
  (:require [clojars.tools.upload-repo :refer :all]
            [clojars.cloudfiles :as cf]
            [clojure.java.io :as io]
            [clojure.test :refer :all])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(deftest improper-args-should-print
  (let [str (with-out-str (-main))]
    (is (re-find #"Usage: repo-path container-name" str))))

(deftest fresh-upload
  (let [conn (cf/connect "" "" "test" "transient")
        file (io/file (io/resource "fake.jar"))
        repo (.toFile (Files/createTempDirectory "repo" (make-array FileAttribute 0)))
        local-file (io/file repo "foo/bar/baz.jar")]
    (.mkdirs (.getParentFile local-file))
    (io/copy file local-file)
    (with-redefs [connect (constantly conn)]
      (with-out-str
        (-main (.getAbsolutePath repo) "test" "xx" "yy")))
    (is (cf/artifact-exists? conn "foo/bar/baz.jar"))))

(deftest fresh-upload-with-sub-path
  (let [conn (cf/connect "" "" "test" "transient")
        file (io/file (io/resource "fake.jar"))
        repo (.toFile (Files/createTempDirectory "repo" (make-array FileAttribute 0)))
        local-file (io/file repo "foo/bar/baz.jar")
        local-file2 (io/file repo "boo/bar/baz.jar")]
    (.mkdirs (.getParentFile local-file))
    (io/copy file local-file)
    (with-redefs [connect (constantly conn)]
      (with-out-str
        (-main (.getAbsolutePath repo) "test" "xx" "yy" "foo")))
    (is (cf/artifact-exists? conn "foo/bar/baz.jar"))
    (is (not (cf/artifact-exists? conn "boo/bar/baz.jar")))))

(deftest upload-existing-with-same-md5-should-skip
  (let [conn (cf/connect "" "" "test" "transient")
        file (io/file (io/resource "fake.jar"))
        repo (.toFile (Files/createTempDirectory "repo" (make-array FileAttribute 0)))
        local-file (io/file repo "foo/bar/baz.jar")]
    (.mkdirs (.getParentFile local-file))
    (io/copy file local-file)
    (cf/put-file conn "foo/bar/baz.jar" file)
    (with-redefs [connect (constantly conn)]
      (let [out (with-out-str
                  (-main (.getAbsolutePath repo) "test" "xx" "yy" "foo"))]
        (is (re-find #"Remote artifact exists" out))))))
