(ns clojars.unit.tools.upload-files-test
  (:require [clojars.cloudfiles :as cf]
            [clojars.tools.upload-files :as upload-files]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(defn transient-connect [args-p conn-p]
  (fn [& args]
    (when args-p (deliver args-p args))
    @(deliver conn-p (apply cf/connect (conj (into [] args) "transient")))))

(deftest improper-args-should-print
  (let [str (with-out-str (upload-files/-main))]
    (is (re-find #"Usage: container-name" str))))

(deftest file-upload-shoud-work
  (let [f (io/file (io/resource "config.clj"))
        args-p (promise)
        conn-p (promise)
        container "test"]
    (with-redefs [upload-files/connect (transient-connect args-p conn-p)]
      (upload-files/-main container "foo" "foo" (.getAbsolutePath f))
      (is (= ["foo" "foo" container] @args-p))
      (is (cf/artifact-exists? @conn-p "config.clj")))))

(deftest multi-file-upload-shoud-work
  (let [files (map #(io/file (io/resource %)) ["config.clj" "fake.jar"])
        conn-p (promise)
        container "test"]
    (with-redefs [upload-files/connect (transient-connect nil conn-p)]
      (apply upload-files/-main container "foo" "foo" (map (memfn getAbsolutePath) files))
      (doseq [f files]
        (is (cf/artifact-exists? @conn-p (.getName f)))))))

(deftest reupload-of-unchanged-file-should-message
  (let [f (io/file (io/resource "config.clj"))
        conn (cf/connect "" "" "test" "transient")]
    (with-redefs [upload-files/connect (constantly conn)]
      (upload-files/-main "test" "foo" "foo" (.getAbsolutePath f))
      (is (cf/artifact-exists? conn "config.clj"))
      (let [out (with-out-str
                  (upload-files/-main "test" "foo" "foo" (.getAbsolutePath f)))]
        (is (re-find #"Remote config.clj exists and has the same md5 checksum" out))))))
