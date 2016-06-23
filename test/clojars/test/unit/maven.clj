(ns clojars.test.unit.maven
  (:use clojure.test clojars.maven)
  (:require [clojars.config :refer [config]]
            [clojure.java.io :as io]))

(deftest pom-to-map-returns-correct-dependencies
  (is (=
        [{:group_name "org.clojure", :jar_name "clojure", :version "1.3.0-beta1" :scope "compile"}
         {:group_name "com.example", :jar_name "versionless", :version "" :scope "compile"}
         {:group_name "org.clojurer", :jar_name "clojure", :version "1.6.0" :scope "provided"}
         {:group_name "midje", :jar_name "midje", :version "1.3-alpha4", :scope "test"}]
     (:dependencies (pom-to-map (.toString (io/resource "test-maven/test-maven.pom")))))))

(deftest pom-to-map-handles-group-and-version-inheritance
  (let [m (pom-to-map (.toString (io/resource "test-maven/test-maven-child.pom")))]
    (is (= "0.0.4" (:version m)))
    (is (= "fake" (:group m)))
    (is (= "child" (:name m)))))

(deftest pom-to-map-parses-scm
  (let [{:keys [tag url]} (:scm (pom-to-map (.toString (io/resource "test-maven/test-maven.pom"))))]
    (is (= "abcde" tag))
    (is (= "http://example.com/example/example" url))))

(deftest pom-to-map-parses-licenses
  (let [[l1 l2] (:licenses (pom-to-map (.toString (io/resource "test-maven/test-maven.pom"))))]
    (is (= "Some License" (:name l1)))
    (is (= "http://example.com/license" (:url l1)))

    (is (= "Some Other License" (:name l2)))
    (is (= "http://example.com/license2" (:url l2)))))

(deftest directory-for-handles-group-names-with-dots
  (is (= (io/file (config :repo) "com" "novemberain" "monger" "1.2.0-alpha1")
         (directory-for {:group_name "com.novemberain"
                         :jar_name "monger"
                         :version "1.2.0-alpha1"}))))

(def snapshot "20120806.052549-1")

(defn expected-file [& [d1 d2 d3 file :as args]]
  (io/file (config :repo) d1 d2 d3 (str file "-" snapshot ".pom")))

(defn snapshot-pom-file-with [jar-map]
  (with-redefs [snapshot-version (constantly snapshot)]
    (snapshot-pom-file jar-map)))

(deftest snapshot-pom-file-handles-single-digit-patch-version
  (is (=
        (expected-file "fake" "test" "0.1.3-SNAPSHOT" "test-0.1.3")
        (snapshot-pom-file-with {:group_name "fake"
                                 :jar_name "test"
                                 :version "0.1.3-SNAPSHOT"}))))

(deftest snapshot-pom-file-handles-multi-digit-patch-version
  (is (=
        (expected-file "fake" "test" "0.11.13-SNAPSHOT" "test-0.11.13")
        (snapshot-pom-file-with {:group_name "fake"
                                 :jar_name "test"
                                 :version "0.11.13-SNAPSHOT"}))))

(deftest snapshot-pom-file-handles-no-patch-version
  (is (=
        (expected-file "fake" "test" "0.1-SNAPSHOT" "test-0.1")
        (snapshot-pom-file-with {:group_name "fake"
                                 :jar_name "test"
                                 :version "0.1-SNAPSHOT"}))))

(deftest snapshot-pom-file-handles-release-candidate-version
  (is (=
        (expected-file "fake" "test" "0.2.1-alpha-SNAPSHOT" "test-0.2.1-alpha")
        (snapshot-pom-file-with {:group_name "fake"
                                 :jar_name "test"
                                 :version "0.2.1-alpha-SNAPSHOT"}))))

;; this might be a good candidate for test.check
(deftest comparing-versions
  (are [op v1 v2] (op (compare-versions v1 v2) 0)
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
    < "1.0.0-008"             "1.0.0-009"))
