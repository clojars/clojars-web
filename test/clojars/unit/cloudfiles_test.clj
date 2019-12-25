(ns clojars.unit.cloudfiles-test
  (:require [clojars.cloudfiles :as cloudfiles]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(defn transient-cloudfiles []
  (cloudfiles/connect "" "" "test" "transient"))

(deftest put-file-should-set-content-type
  (let [f (io/file (io/resource "config.clj"))
        conn (transient-cloudfiles)]
    (cloudfiles/put-file conn "foo.clj" f)
    (is (= "text/plain" (:content-type (cloudfiles/artifact-metadata conn "foo.clj"))))
    
    (cloudfiles/put-file conn "foo.blah" f)
    (is (= "application/unknown" (:content-type (cloudfiles/artifact-metadata conn "foo.blah"))))

    (cloudfiles/put-file conn "foo.gz" f)
    (is (= "application/gzip" (:content-type (cloudfiles/artifact-metadata conn "foo.gz"))))))

(deftest remove-artifact-should-work
  (let [cf (transient-cloudfiles)
        path "ham/biscuit/fake.jar"]
    (cloudfiles/put-file cf path (io/file (io/resource "fake.jar")))
    (is (cloudfiles/artifact-exists? cf path))
    (cloudfiles/remove-artifact cf "ham/biscuit")
    (is (cloudfiles/artifact-exists? cf path))
    (cloudfiles/remove-artifact cf path)
    (is (not (cloudfiles/artifact-exists? cf path)))))

(deftest metadata-seq-should-work
  (let [cf (transient-cloudfiles)
        path1 "ham/biscuit/fake.jar"
        path2 "ham/sandwich/fake.jar"]
    (cloudfiles/put-file cf path1 (io/file (io/resource "fake.jar")))
    (cloudfiles/put-file cf path2 (io/file (io/resource "fake.jar")))
    (is (cloudfiles/artifact-exists? cf path1))
    (is (cloudfiles/artifact-exists? cf path2))
    (let [all (cloudfiles/metadata-seq cf)]
      (is (= 2 (count all)))
      (is (= #{path1 path2} (->> all (map :name) set))))
    (let [biscuit (cloudfiles/metadata-seq cf {:in-directory "ham/biscuit"})]
      (is (= 1 (count biscuit)))
      (is (= path1 (-> biscuit first :name))))))
