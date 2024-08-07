(ns clojars.unit.maven-test
  (:require
   [clojars.maven :as maven]
   [clojure.java.io :as io]
   [clojure.test :refer [are deftest is]]))

(deftest pom-to-map-returns-correct-dependencies
  (is (=
       [{:group_name "org.clojure", :jar_name "clojure", :version "1.3.0-beta1" :scope "compile"}
        {:group_name "com.example", :jar_name "versionless", :version "" :scope "compile"}
        {:group_name "org.clojurer", :jar_name "clojure", :version "1.6.0" :scope "provided"}
        {:group_name "midje", :jar_name "midje", :version "1.3-alpha4", :scope "test"}]
       (:dependencies (maven/pom-to-map (.toString (io/resource "test-maven/test-maven.pom")))))))

(deftest pom-to-map-handles-group-and-version-inheritance
  (let [m (maven/pom-to-map (.toString (io/resource "test-maven/test-maven-child.pom")))]
    (is (= "0.0.4" (:version m)))
    (is (= "fake" (:group m)))
    (is (= "child" (:name m)))))

(deftest pom-to-map-parses-scm
  (let [{:keys [tag url]} (:scm (maven/pom-to-map (.toString (io/resource "test-maven/test-maven.pom"))))]
    (is (= "abcde" tag))
    (is (= "http://example.com/example/example" url))))

(deftest pom-to-map-parses-licenses
  (let [[l1 l2] (:licenses (maven/pom-to-map (.toString (io/resource "test-maven/test-maven.pom"))))]
    (is (= "Some License" (:name l1)))
    (is (= "http://example.com/license" (:url l1)))

    (is (= "Some Other License" (:name l2)))
    (is (= "http://example.com/license2" (:url l2)))))

;; this might be a good candidate for test.check
(deftest comparing-versions
  #_{:clj-kondo/ignore [:equals-expected-position]}
  (are [op v1 v2] (op (maven/compare-versions v1 v2) 0)
    = "0.0.1"                 "0.0.1"
    > "0.0.1"                 "0.0.1-SNAPSHOT"
    > "0.0.1-alpha2"          "0.0.1-alpha1"
    < "0.0.1-alpha"           "0.0.1-alpha1"
    > "0.0.1-beta1"           "0.0.1-alpha2"
    > "0.0.1-beta2"           "0.0.1-alpha"
    > "0.0.1-alpha22"         "0.0.1-alpha1"
    < "0.0.1-alpha2"          "0.0.1"
    < "0.0.1"                 "0.0.2"
    < "1.0.0"                 "2.0.0"
    < "8.2.0.Final"           "10.0.0.CR5"
    > "10.0.0.Final"          "10.0.0.CR5"
    > "10.0.0.Final"          "10.0.0.RC5"
    > "10.0.0.Final-SNAPSHOT" "10.0.0.RC5"
    < "10.0.0.Final-SNAPSHOT" "10.0.0.Final"
    > "10.0.0-SNAPSHOT"       "10.0.0.RC5"
    < "1.2.3.455"             "1.2.3.456"
    < "1"                     "2"
    < "1.0.0-rc1"             "1.0.0-rc10"
    < "1.0.0-rc9"             "1.0.0-rc10"
    < "1.0.0-rc10"            "1.0.0-rc20"
    < "1-rc1-SNAPSHOT"        "1-rc1"
    < "1.0.0-008"             "1.0.0-009"
    < "1.0.0-rc2-SNAPSHOT"    "1.0.0-1-SNAPSHOT"
    < "1.0.0-SNAPSHOT"        "1.0.0-1-SNAPSHOT"))
