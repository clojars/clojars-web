(ns clojars.unit.tools.remove-deletions-test
  (:require [clojars.cloudfiles :as cf]
            [clojars.tools.remove-deletions :as remove-deletions]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(deftest improper-args-should-print
  (let [str (with-out-str (remove-deletions/-main))]
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
    (with-redefs [remove-deletions/connect (constantly conn)]
      (with-out-str
        (remove-deletions/-main (.getAbsolutePath repo) "test" "xx" "yy")))
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
    (with-redefs [remove-deletions/connect (constantly conn)]
      (with-out-str
        (remove-deletions/-main (.getAbsolutePath repo) "test" "xx" "yy" "foo")))
    (is (cf/artifact-exists? conn "foo/bar/baz.jar"))
    (is (cf/artifact-exists? conn "boo/far/baz.jar"))
    (is (not (cf/artifact-exists? conn "foo/far/baz.jar")))))

