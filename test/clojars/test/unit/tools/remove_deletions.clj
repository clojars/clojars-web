(ns clojars.test.unit.tools.remove-deletions
  (:require [clojars.tools.remove-deletions :refer :all]
            [clojars.cloudfiles :as cf]
            [clojure.java.io :as io]
            [clojure.test :refer :all])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(deftest improper-args-should-print
  (let [str (with-out-str (-main))]
    (is (re-find #"Usage: repo-path container-name" str))))

(deftest removal-of-missing-local
  (let [conn (cf/connect "" "" "test" "transient")
        file (io/file (io/resource "fake.jar"))
        repo (.toFile (Files/createTempDirectory "repo" (make-array FileAttribute 0)))
        local-file (io/file repo "foo/bar/baz.jar")]
    (cf/put-file conn "foo/bar/baz.jar" file)
    (cf/put-file conn "boo/far/baz.jar" file)
    (.mkdirs (.getParentFile local-file))
    (io/copy file local-file)
    (with-redefs [connect (constantly conn)]
      (with-out-str
        (-main (.getAbsolutePath repo) "test" "xx" "yy")))
    (is (cf/artifact-exists? conn "foo/bar/baz.jar"))
    (is (not (cf/artifact-exists? conn "boo/far/baz.jar")))))

(deftest removal-with-subpath
  (let [conn (cf/connect "" "" "test" "transient")
        file (io/file (io/resource "fake.jar"))
        repo (.toFile (Files/createTempDirectory "repo" (make-array FileAttribute 0)))
        local-file (io/file repo "foo/bar/baz.jar")]
    (cf/put-file conn "foo/bar/baz.jar" file)
    (cf/put-file conn "foo/far/baz.jar" file)
    (cf/put-file conn "boo/far/baz.jar" file)
        (.mkdirs (.getParentFile local-file))
    (io/copy file local-file)
    (with-redefs [connect (constantly conn)]
      (with-out-str
        (-main (.getAbsolutePath repo) "test" "xx" "yy" "foo")))
    (is (cf/artifact-exists? conn "foo/bar/baz.jar"))
    (is (cf/artifact-exists? conn "boo/far/baz.jar"))
    (is (not (cf/artifact-exists? conn "foo/far/baz.jar")))))

