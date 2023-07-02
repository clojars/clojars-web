(ns clojars.integration.repo-indexing-test
  (:refer-clojure :exclude [key])
  (:require
   [clojars.event :as event]
   [clojars.s3 :as s3]
   [clojars.test-helper :as help]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [matcher-combinators.test]))

(use-fixtures :each help/run-test-app)

(defn key
  [path n]
  (if path
    (format "%s/%s" path n)
    n))

(defn write-data
  [repo-bucket path]
  (doseq [k ["a.pom" "a.jar"]]
    (s3/put-object repo-bucket (key path k) (io/input-stream (io/resource "fake.jar")))))

(defn index-key
  [path]
  (key path "index.html"))

(deftest index-for-path-should-work-for-valid-paths
  (let [{:keys [event-emitter repo-bucket]} help/system]
    (doseq [path [nil "a" "abc/b" "a_b/c-1/1.0.4+Foo"]
            :let [key (index-key path)]]
      (testing (format "with path '%s'" path)
        (write-data repo-bucket path)
        (event/emit event-emitter :repo-path-needs-index {:path path})
        (when (is (help/wait-for-s3-key repo-bucket key))
          (when (is (s3/object-exists? repo-bucket key))
            (let [index (with-open [is (s3/get-object-stream repo-bucket key)]
                          (slurp is))]
              (is (re-find #"a\.pom" index))
              (is (re-find #"a\.jar" index))
              (is (not (re-find #"index\.html" index))))))))))
