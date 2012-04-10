(ns clojars.test.integration.jars
  (:use clojure.test
        kerodon.core
        kerodon.test
        clojars.test.integration.steps)
  (:require [clojars.web :as web]
            [clojars.test.test-helper :as help]))

(help/use-fixtures)

(deftest jars-can-be-viewed
  (-> (session web/clojars-app)
      (register-as "dantheman" "test@example.org" "password" valid-ssh-key))
  (scp valid-ssh-key "test.jar" "test-0.0.1/test.pom")
  (scp valid-ssh-key "test.jar" "test-0.0.2/test.pom")
  (scp valid-ssh-key "test.jar" "test-0.0.3-SNAPSHOT/test.pom")
  (scp valid-ssh-key "test.jar" "test-0.0.3-SNAPSHOT/test.pom")
  (-> (session web/clojars-app)
      (visit "/fake/test")
      (within [:article :h1]
              (has (text? "fake/test")))
      (within [:.lein :pre]
              (has (text? "[fake/test \"0.0.3-SNAPSHOT\"]")))
      (within [:.versions :ul]
              (has (text? "0.0.3-SNAPSHOT0.0.20.0.1")))
      (follow "show all versions (3 total)")
      (within [:.versions :ul]
              (has (text? "0.0.3-SNAPSHOT0.0.20.0.1")))))

(deftest jars-with-only-snapshots-can-be-viewed
  (-> (session web/clojars-app)
      (register-as "dantheman" "test@example.org" "password" valid-ssh-key))
  (scp valid-ssh-key "test.jar" "test-0.0.3-SNAPSHOT/test.pom")
  (-> (session web/clojars-app)
      (visit "/fake/test")
      (within [:article :h1]
              (has (text? "fake/test")))
      (within [:.lein :pre]
              (has (text? "[fake/test \"0.0.3-SNAPSHOT\"]")))
      (follow "show all versions (1 total)")
      (within [:.versions :ul]
              (has (text? "0.0.3-SNAPSHOT")))))

(deftest canonical-jars-can-be-viewed
  (-> (session web/clojars-app)
      (register-as "dantheman" "test@example.org" "password" valid-ssh-key))
  (scp valid-ssh-key "fake.jar" "fake-0.0.1/fake.pom")
  (scp valid-ssh-key "fake.jar" "fake-0.0.2/fake.pom")
  (scp valid-ssh-key "fake.jar" "fake-0.0.3-SNAPSHOT/fake.pom")
  (-> (session web/clojars-app)
      (visit "/fake")
      (within [:article :h1]
              (has (text? "fake")))
      (within [:.lein :pre]
              (has (text? "[fake \"0.0.3-SNAPSHOT\"]")))
      (within [:.versions :ul]
              (has (text? "0.0.3-SNAPSHOT0.0.20.0.1")))))

(deftest specific-versions-can-be-viewed
  (-> (session web/clojars-app)
      (register-as "dantheman" "test@example.org" "password" valid-ssh-key))
  (scp valid-ssh-key "test.jar" "test-0.0.1/test.pom")
  (scp valid-ssh-key "test.jar" "test-0.0.2/test.pom")
  (scp valid-ssh-key "test.jar" "test-0.0.3-SNAPSHOT/test.pom")
  (-> (session web/clojars-app)
      (visit "/fake/test")
      (follow "0.0.3-SNAPSHOT")
      (within [:article :h1]
              (has (text? "fake/test")))
      (within [:.lein :pre]
              (has (text? "[fake/test \"0.0.3-SNAPSHOT\"]")))
      (within [:.versions :ul]
              (has (text? "0.0.3-SNAPSHOT0.0.20.0.1")))))

(deftest specific-canonical-versions-can-be-viewed
  (-> (session web/clojars-app)
      (register-as "dantheman" "test@example.org" "password" valid-ssh-key))
  (scp valid-ssh-key "fake.jar" "fake-0.0.1/fake.pom")
  (scp valid-ssh-key "fake.jar" "fake-0.0.2/fake.pom")
  (scp valid-ssh-key "fake.jar" "fake-0.0.3-SNAPSHOT/fake.pom")
  (-> (session web/clojars-app)
      (visit "/fake")
      (follow "0.0.1")
      (within [:article :h1]
              (has (text? "fake")))
      (within [:.lein :pre]
              (has (text? "[fake \"0.0.1\"]")))
      (within [:.versions :ul]
              (has (text? "0.0.3-SNAPSHOT0.0.20.0.1")))))