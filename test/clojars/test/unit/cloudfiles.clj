(ns clojars.test.unit.cloudfiles
  (:require [clojars.cloudfiles :refer :all]
            [clojure.java.io :as io]
            [clojure.test :refer :all]))

(deftest put-file-should-set-content-type
  (let [f (io/file (io/resource "config.clj"))
        conn (connect "" "" "test" "transient")]
    (put-file conn "foo.clj" f)
    (is (= "text/plain" (:content-type (artifact-metadata conn "foo.clj"))))
    
    (put-file conn "foo.blah" f)
    (is (= "application/unknown" (:content-type (artifact-metadata conn "foo.blah"))))

    (put-file conn "foo.gz" f)
    (is (= "application/gzip" (:content-type (artifact-metadata conn "foo.gz"))))))
