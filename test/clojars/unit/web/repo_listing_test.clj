(ns clojars.unit.web.repo-listing-test
  (:require
   [clojars.s3 :as s3]
   [clojars.test-helper :as help]
   [clojars.web.repo-listing :as repo-listing]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [matcher-combinators.test]))

(def ^:private tmp-cache (io/file help/tmp-dir "clojars" "repo-listing-cache"))

(defn- with-tmp-cache
  [f]
  (help/delete-file-recursively tmp-cache)
  (f))

(use-fixtures :each with-tmp-cache)

(defn- mock-repo-lister
  []
  {:cache-path  (.getAbsolutePath tmp-cache)
   :repo-bucket (s3/mock-s3-client)})

(deftest index-for-path-should-lookup-and-cache-valid-paths
  (doseq [path [nil "a/" "abc/b/" "a_b/c-1/1.0.4+Foo/"]]
    (testing (format "with path '%s'" path)
      (let [lister (mock-repo-lister)
            result (repo-listing/index-for-path lister path)]
        (is (= [['-list-entries [path]]] (s3/get-mock-calls (:repo-bucket lister))))
        (is (.exists (repo-listing/cache-file (:cache-path lister) path)))
        (is (match? {:status 404} result))))))

(deftest index-for-path-should-not-lookup-or-cache-invalid-paths
  (doseq [path ["a/b/=/" "?foo=bar/" "abc.b/"]]
    (testing (format "with path '%s'" path)
      (let [lister (mock-repo-lister)
            result (repo-listing/index-for-path lister path)]
        (is (empty? (s3/get-mock-calls (:repo-bucket lister))))
        (is (not (.exists (repo-listing/cache-file (:cache-path lister) path))))
        (is (match? {:status 404} result))))))
