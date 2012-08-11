(ns clojars.test.unit.maven
  (:use clojure.test clojars.maven)
  (:require [clojars.config :refer [config]]
            [clojure.java.io :as io]))

(deftest pom-to-map-returns-corrects-dependencies
  (is (=
        [{:group_name "org.clojure", :jar_name "clojure", :version "1.3.0-beta1" :dev false}
         {:group_name "org.clojurer", :jar_name "clojure", :version "1.6.0" :dev false}
         {:group_name "midje", :jar_name "midje", :version "1.3-alpha4", :dev true}]
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
