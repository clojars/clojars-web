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
