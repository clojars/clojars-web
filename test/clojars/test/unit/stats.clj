(ns clojars.test.unit.stats
  (:require [clojars.stats :refer :all]
            [clojure.test :refer :all]
            [com.stuartsierra.component :as component])
  (:import com.google.common.jimfs.Jimfs
           com.google.common.jimfs.Configuration
           java.nio.file.Files
           java.nio.file.OpenOption
           java.nio.file.attribute.FileAttribute))

(def download-counts {["a" "x"] {"1" 1
                                 "2" 2}
                      ["b" "y"] {"1" 4}
                      ["c" "z"] {"1" 5}})

(deftest map-stats-computes-group-downloads
  (let [stats (->MapStats download-counts)]
    (is (= 3 (download-count stats "a" "x")))
    (is (= 4 (download-count stats "b" "y")))
    (is (= 5 (download-count stats "c" "z")))))

(deftest map-stats-looks-up-version-downloads
  (let [stats (->MapStats download-counts)]
    (is (= 1 (download-count stats "a" "x" "1")))
    (is (= 2 (download-count stats "a" "x" "2")))
    (is (= 4 (download-count stats "b" "y" "1")))
    (is (= 5 (download-count stats "c" "z" "1")))))

(deftest map-stats-returns-zero-for-missing-values
  (let [stats (->MapStats download-counts)]
    (is (= 0 (download-count stats "a" "y" "1")))
    (is (= 0 (download-count stats "a" "x" "3")))
    (is (= 0 (download-count stats "d" "d" "1")))))

(defn fs-with-stats-file [dir string]
  (let [fs (Jimfs/newFileSystem (Configuration/unix))]
    (Files/createDirectory (.getPath fs dir (make-array String 0))
                           (make-array FileAttribute 0))
    (Files/write (.getPath fs dir (into-array String ["all.edn"]))
                 (.getBytes string)
                 (make-array OpenOption 0))
    fs))

(deftest file-stats-uses-file
  (let [stats (component/start (assoc (file-stats "some-dir")
                                      :fs-factory #(fs-with-stats-file "some-dir"
                                                                       (pr-str download-counts))))]
    (try
      (is (= 5 (download-count stats "c" "z")))
      (finally
        (component/stop stats)))))

(deftest file-stats-memoizes-file-reading
  (let [stats (component/start (assoc (file-stats "some-dir")
                                      :fs-factory #(fs-with-stats-file "some-dir"
                                                                       (pr-str download-counts))))]
    (try
      (download-count stats "c" "z")
      (Files/write (.getPath (:fs stats) "some-dir" (into-array String ["all.edn"]))
                   (.getBytes (pr-str {["c" "z"] {"1" 722}}))
                   (make-array OpenOption 0))
      (is (= 5 (download-count stats "c" "z")))
      ;; TODO test cache expiration
      (finally
        (component/stop stats)))))

(deftest format-stats-with-commas
  (is "0" (format-stats 0))
  (is "1" (format-stats 1))
  (is "-1" (format-stats -1))
  (is "1" (format-stats 1.25129))
  (is "999" (format-stats 999))
  (is "1,000" (format-stats 1000))
  (is "2,123,512" (format-stats 2123512)))
