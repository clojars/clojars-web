(ns clojars.test.unit.maven
  (:use clojure.test clojars.maven))

(deftest pom-to-map-returns-corrects-dependencies
  (is (=
     (:dependencies (pom-to-map "test-resources/test-maven/test-maven.pom")) 
      [:org.clojure/clojure "1.3.0-beta1" :org.clojurer/clojure "1.6.0"]
     )))
