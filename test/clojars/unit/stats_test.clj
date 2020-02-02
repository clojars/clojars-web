(ns clojars.unit.stats-test
  (:require [clojars.s3 :as s3]
            [clojars.stats :as stats]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(def download-counts
  {["a" "x"] {"1" 1
              "2" 2}
   ["b" "y"] {"1" 4}
   ["c" "z"] {"1" 5}})

(defn as-stream [data]
  (binding [*print-length* nil]
    (-> data
        (pr-str)
        (.getBytes)
        (io/input-stream))))

(defn s3-stats []
  (let [s3 (s3/mock-s3-client)
        stats (assoc (stats/artifact-stats) :stats-bucket s3)]
    (s3/put-object s3 "all.edn" (as-stream download-counts))
    [stats s3]))

(deftest stats-computes-group-downloads
  (let [[stats] (s3-stats)]
    (is (= 3 (stats/download-count stats "a" "x")))
    (is (= 4 (stats/download-count stats "b" "y")))
    (is (= 5 (stats/download-count stats "c" "z")))))

(deftest stats-looks-up-version-downloads
  (let [[stats] (s3-stats)]
    (is (= 1 (stats/download-count stats "a" "x" "1")))
    (is (= 2 (stats/download-count stats "a" "x" "2")))
    (is (= 4 (stats/download-count stats "b" "y" "1")))
    (is (= 5 (stats/download-count stats "c" "z" "1")))))

(deftest stats-returns-zero-for-missing-values
  (let [[stats] (s3-stats)]
    (is (= 0 (stats/download-count stats "a" "y" "1")))
    (is (= 0 (stats/download-count stats "a" "x" "3")))
    (is (= 0 (stats/download-count stats "d" "d" "1")))))

(deftest stats-memoize-s3-reading
  (let [[stats s3] (s3-stats)]
    (is (= 5 (stats/download-count stats "c" "z")))
    (s3/put-object s3 "all.edn" (as-stream {["c" "z"] {"1" 722}}))
    (is (= 5 (stats/download-count stats "c" "z")))))

(deftest format-stats-with-commas
  (is "0" (stats/format-stats 0))
  (is "1" (stats/format-stats 1))
  (is "-1" (stats/format-stats -1))
  (is "1" (stats/format-stats 1.25129))
  (is "999" (stats/format-stats 999))
  (is "1,000" (stats/format-stats 1000))
  (is "2,123,512" (stats/format-stats 2123512)))
