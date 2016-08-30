(ns clojars.test.unit.cloudfiles
  (:require [clojars.cloudfiles :refer :all]
            [clojure.java.io :as io]
            [clojure.test :refer :all]))

(defn transient-cloudfiles []
  (connect "" "" "test" "transient"))

(deftest put-file-should-set-content-type
  (let [f (io/file (io/resource "config.clj"))
        conn (transient-cloudfiles)]
    (put-file conn "foo.clj" f)
    (is (= "text/plain" (:content-type (artifact-metadata conn "foo.clj"))))
    
    (put-file conn "foo.blah" f)
    (is (= "application/unknown" (:content-type (artifact-metadata conn "foo.blah"))))

    (put-file conn "foo.gz" f)
    (is (= "application/gzip" (:content-type (artifact-metadata conn "foo.gz"))))))

(deftest remove-artifact-should-work
  (let [cf (transient-cloudfiles)
        path "ham/biscuit/fake.jar"]
    (put-file cf path (io/file (io/resource "fake.jar")))
    (is (artifact-exists? cf path))
    (remove-artifact cf "ham/biscuit")
    (is (artifact-exists? cf path))
    (remove-artifact cf path)
    (is (not (artifact-exists? cf path)))))

(deftest metadata-seq-should-work
  (let [cf (transient-cloudfiles)
        path1 "ham/biscuit/fake.jar"
        path2 "ham/sandwich/fake.jar"]
    (put-file cf path1 (io/file (io/resource "fake.jar")))
    (put-file cf path2 (io/file (io/resource "fake.jar")))
    (is (artifact-exists? cf path1))
    (is (artifact-exists? cf path2))
    (let [all (metadata-seq cf)]
      (is (= 2 (count all)))
      (is (= #{path1 path2} (->> all (map :name) set))))
    (let [biscuit (metadata-seq cf {:in-directory "ham/biscuit"})]
      (is (= 1 (count biscuit)))
      (is (= path1 (-> biscuit first :name))))))
