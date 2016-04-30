(ns clojars.test.unit.tools.upload-files
  (:require [clojars.tools.upload-files :refer :all]
            [clojars.cloudfiles :as cf]
            [clojure.java.io :as io]
            [clojure.test :refer :all]))

(defn transient-connect [args-p conn-p]
  (fn [& args]
    (when args-p (deliver args-p args))
    @(deliver conn-p (apply cf/connect (conj (into [] args) "transient")))))

(deftest improper-args-should-print
  (let [str (with-out-str (-main))]
    (is (re-find #"Usage: container-name" str))))

(deftest file-upload-shoud-work
  (let [f (io/file (io/resource "config.clj"))
        args-p (promise)
        conn-p (promise)
        container "test"]
    (with-redefs [connect (transient-connect args-p conn-p)]
      (-main container "foo" "foo" (.getAbsolutePath f))
      (is (= ["foo" "foo" container] @args-p))
      (is (cf/artifact-exists? @conn-p "config.clj")))))

(deftest multi-file-upload-shoud-work
  (let [files (map #(io/file (io/resource %)) ["config.clj" "fake.jar"])
        conn-p (promise)
        container "test"]
    (with-redefs [connect (transient-connect nil conn-p)]
      (apply -main container "foo" "foo" (map (memfn getAbsolutePath) files))
      (doseq [f files]
        (is (cf/artifact-exists? @conn-p (.getName f)))))))

(deftest reupload-of-unchanged-file-should-message
  (let [f (io/file (io/resource "config.clj"))
        conn (cf/connect "" "" "test" "transient")]
    (with-redefs [connect (constantly conn)]
      (-main "test" "foo" "foo" (.getAbsolutePath f))
      (is (cf/artifact-exists? conn "config.clj"))
      (let [out (with-out-str
                  (-main "test" "foo" "foo" (.getAbsolutePath f)))]
        (is (re-find #"Remote config.clj exists and has the same md5 checksum" out))))))
