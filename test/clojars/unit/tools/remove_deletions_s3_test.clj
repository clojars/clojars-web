(ns clojars.unit.tools.remove-deletions-s3-test
  (:require [clojars.s3 :as s3]
            [clojars.tools.remove-deletions-s3 :as remove-deletions-s3]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(deftest improper-args-should-print
  (let [str (with-out-str (remove-deletions-s3/-main))]
    (is (re-find #"Usage: repo-path bucket-name" str))))

(deftest removal-of-missing-local
  (let [s3-client (s3/mock-s3-client)
        file (io/file (io/resource "fake.jar"))
        repo (.toFile (Files/createTempDirectory "repo" (make-array FileAttribute 0)))
        local-file (io/file repo "foo/bar/baz.jar")]
    (s3/put-file s3-client "foo/bar/baz.jar" file)
    (s3/put-file s3-client "boo/far/baz.jar" file)
    (.mkdirs (.getParentFile local-file))
    (io/copy file local-file)
    (with-redefs [remove-deletions-s3/create-s3-bucket (constantly s3-client)]
      (with-out-str
        (remove-deletions-s3/-main (.getAbsolutePath repo) "bucket" "region" "key" "secret")))
    (is (s3/object-exists? s3-client "foo/bar/baz.jar"))
    (is (not (s3/object-exists? s3-client "boo/far/baz.jar")))))

(deftest removal-with-subpath
  (let [s3-client (s3/mock-s3-client)
        file (io/file (io/resource "fake.jar"))
        repo (.toFile (Files/createTempDirectory "repo" (make-array FileAttribute 0)))
        local-file (io/file repo "foo/bar/baz.jar")]
    (s3/put-file s3-client "foo/bar/baz.jar" file)
    (s3/put-file s3-client "foo/far/baz.jar" file)
    (s3/put-file s3-client "boo/far/baz.jar" file)
    (.mkdirs (.getParentFile local-file))
    (io/copy file local-file)
    (with-redefs [remove-deletions-s3/create-s3-bucket (constantly s3-client)]
      (with-out-str
        (remove-deletions-s3/-main (.getAbsolutePath repo) "bucket" "region" "key" "secret" "foo")))
    (is (s3/object-exists? s3-client "foo/bar/baz.jar"))
    (is (s3/object-exists? s3-client "boo/far/baz.jar"))
    (is (not (s3/object-exists? s3-client "foo/far/baz.jar")))))

