(ns clojars.integration.jars-test
  (:require [clojars.integration.steps :refer [inject-artifacts-into-repo!]]
            [clojars.test-helper :as help]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest use-fixtures]]
            [kerodon.core :refer [follow session visit within]]
            [kerodon.test :refer [has some-text? text?]]
            [net.cgrand.enlive-html :as html]))

(use-fixtures :each
  help/default-fixture
  help/with-clean-database)

(deftest jars-can-be-viewed
  (inject-artifacts-into-repo! help/*db* "someuser" "test.jar" "test-0.0.1/test.pom")
  (inject-artifacts-into-repo! help/*db* "someuser" "test.jar" "test-0.0.2/test.pom")
  (inject-artifacts-into-repo! help/*db* "someuser" "test.jar" "test-0.0.3-SNAPSHOT/test.pom")
  (inject-artifacts-into-repo! help/*db* "someuser" "test.jar" "test-0.0.3-SNAPSHOT/test.pom")
  (-> (session (help/app))
      (visit "/org.clojars.dantheman/test")
      (within [:div#jar-title :h1 :a]
              (has (text? "org.clojars.dantheman/test")))
      (within [[:.package-config-example (html/nth-of-type 2)] :pre]
              (has (text? "[org.clojars.dantheman/test \"0.0.2\"]")))
      (within [:ul#versions]
              (has (text? "0.0.3-SNAPSHOT0.0.20.0.1")))))

(deftest jars-with-only-snapshots-can-be-viewed
  (inject-artifacts-into-repo! help/*db* "someuser" "test.jar" "test-0.0.3-SNAPSHOT/test.pom")
  (-> (session (help/app))
      (visit "/org.clojars.dantheman/test")
      (within [:div#jar-title :h1 :a]
              (has (text? "org.clojars.dantheman/test")))
      (within [[:.package-config-example (html/nth-of-type 2)] :pre]
              (has (text? "[org.clojars.dantheman/test \"0.0.3-SNAPSHOT\"]")))
      (within [:span.commit-url]
              (has (text? " with this git tree")))
      (within [:ul#versions]
              (has (text? "0.0.3-SNAPSHOT")))))

(deftest canonical-jars-can-be-viewed
  (inject-artifacts-into-repo! help/*db* "someuser" "fake.jar" "fake-0.0.1/fake.pom")
  (inject-artifacts-into-repo! help/*db* "someuser" "fake.jar" "fake-0.0.2/fake.pom")
  (inject-artifacts-into-repo! help/*db* "someuser" "fake.jar" "fake-0.0.3-SNAPSHOT/fake.pom")
  (-> (session (help/app))
      (visit "/fake")
      (within [:div#jar-title :h1 :a]
              (has (text? "fake")))
      (within [[:.package-config-example (html/nth-of-type 2)] :pre]
              (has (text? "[fake \"0.0.2\"]")))
      (within [:ul#versions]
              (has (text? "0.0.3-SNAPSHOT0.0.20.0.1")))))

(deftest specific-versions-can-be-viewed
  (inject-artifacts-into-repo! help/*db* "someuser" "test.jar" "test-0.0.1/test.pom")
  (inject-artifacts-into-repo! help/*db* "someuser" "test.jar" "test-0.0.2/test.pom")
  (inject-artifacts-into-repo! help/*db* "someuser" "test.jar" "test-0.0.3-SNAPSHOT/test.pom")
  (-> (session (help/app))
      (visit "/org.clojars.dantheman/test")
      (follow "0.0.3-SNAPSHOT")
      (within [:div#jar-title :h1 :a]
              (has (text? "org.clojars.dantheman/test")))
      (within [[:.package-config-example (html/nth-of-type 2)] :pre]
              (has (text? "[org.clojars.dantheman/test \"0.0.3-SNAPSHOT\"]")))
      (within [:ul#versions]
              (has (text? "0.0.3-SNAPSHOT0.0.20.0.1")))))

(deftest specific-canonical-versions-can-be-viewed
  (inject-artifacts-into-repo! help/*db* "someuser" "fake.jar" "fake-0.0.1/fake.pom")
  (inject-artifacts-into-repo! help/*db* "someuser" "fake.jar" "fake-0.0.2/fake.pom")
  (inject-artifacts-into-repo! help/*db* "someuser" "fake.jar" "fake-0.0.3-SNAPSHOT/fake.pom")
  (-> (session (help/app))
      (visit "/fake")
      (follow "0.0.1")
      (within [:div#jar-title :h1 :a]
              (has (text? "fake")))
      (within [[:.package-config-example (html/nth-of-type 2)] :pre]
              (has (text? "[fake \"0.0.1\"]")))
      (within [:ul#versions]
              (has (text? "0.0.3-SNAPSHOT0.0.20.0.1")))))

(deftest canonical-jars-can-view-dependencies
  (inject-artifacts-into-repo! help/*db* "someuser" "fake.jar" "fake-0.0.1/fake.pom")
  (inject-artifacts-into-repo! help/*db* "someuser" "fake.jar" "fake-0.0.2/fake.pom")
  (-> (session (help/app))
      (visit "/fake")
      (within [:ul#dependencies]
               (has (text? "org.clojure/clojure 1.3.0-beta1")))))

(deftest shadow-jars-have-a-message
  (inject-artifacts-into-repo! help/*db* "someuser" "fake.jar"
    (help/rewrite-pom (io/file (io/resource "fake-0.0.1/fake.pom"))
      {:groupId    "org.tcrawley"
       :artifactId "dynapath"}))
  (-> (session (help/app))
      (visit "/org.tcrawley/dynapath")
      (within [:div#jar-title :div#notice]
              (has (some-text? "may shadow a release")))))

