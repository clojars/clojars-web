(ns clojars.test.unit.maven
  (:use clojure.test clojars.maven)
  (:require [clojars.config :refer [config]]
            [clojure.java.io :as io]))

(deftest pom-to-map-returns-corrects-dependencies
  (is (=
        [{:group_name "org.clojure", :jar_name "clojure", :version "1.3.0-beta1"}
         {:group_name "org.clojurer", :jar_name "clojure", :version "1.6.0"}]
     (:dependencies (pom-to-map "test-resources/test-maven/test-maven.pom")))))

(deftest directory-for-handles-normal-group-name
  (is (= (io/file (config :repo) "fake" "test" "1.0.0")
         (directory-for {:group_name "fake"
                         :jar_name "test"
                         :version "1.0.0"})))
         )
(deftest directory-for-handles-group-names-with-dots
  (is (= (io/file (config :repo) "com" "novemberain" "monger" "1.2.0-alpha1")
         (directory-for {:group_name "com.novemberain"
                         :jar_name "monger"
                         :version "1.2.0-alpha1"}))))

(deftest snapshot-pom-file-handles-single-digit-version
  (is (=
        (io/file (config :repo) "fake" "test" "0.1.3-SNAPSHOT" "test-0.1.3-20120806.052549-1.pom")
        (with-redefs
             [snapshot-version (constantly "20120806.052549-1")]
             (snapshot-pom-file {:group_name "fake"
                               :jar_name "test"
                               :version "0.1.3-SNAPSHOT"})))))

(deftest snapshot-pom-file-handles-multi-digit-version
  (is (=
        (io/file (config :repo) "fake" "test" "0.11.13-SNAPSHOT" "test-0.11.13-20120806.052549-1.pom")
        (with-redefs
             [snapshot-version (constantly "20120806.052549-1")]
             (snapshot-pom-file {:group_name "fake"
                               :jar_name "test"
                               :version "0.11.13-SNAPSHOT"})))))
