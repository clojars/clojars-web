(ns clojars.test.unit.maven
  (:use clojure.test clojars.maven))

(deftest pom-to-map-returns-corrects-dependencies
  (is (=
        [{:group_name "org.clojure", :jar_name "clojure", :version "1.3.0-beta1"}
         {:group_name "org.clojurer", :jar_name "clojure", :version "1.6.0"}]
     (:dependencies (pom-to-map "test-resources/test-maven/test-maven.pom")))))
