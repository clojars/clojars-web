(ns clojars.unit.tools.export-poms-test
  (:require
   [clojars.db :as db]
   [clojars.maven :as maven]
   [clojars.test-helper :as help]
   [clojars.tools.export-poms :as export-poms]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is use-fixtures]]
   [matcher-combinators.test])
  (:import
   (java.sql
    Timestamp)))

(use-fixtures :each
  help/default-fixture
  help/with-clean-database)

(defn- add-jar
  [at {:as jar :keys [group]}]
  (with-redefs [db/get-time (constantly (Timestamp. (.getTime at)))]
    (help/add-verified-group "testuser" group)
    (db/add-jar help/*db* "testuser" jar)))

(deftest test-export-all-poms
  (let [temp-repo (help/create-tmp-dir "repo")]
    ;; Given: jars in the database at various times, with a SNAPSHOT that
    ;; overrides the description in a later release
    (add-jar #inst "2025-01-01"
             {:group "test.clojars"
              :name "testing"
              :version "1.0"
              :description "testing"})
    (add-jar #inst "2025-01-02"
             {:group "test.clojars"
              :name "testing"
              :version "1.1"
              :description "testing again"
              :packaging "pom"})
    (add-jar #inst "2025-01-03"
             {:group "test.clojars"
              :name "testing2"
              :version "1.0-SNAPSHOT"
              :description "no"})
    (add-jar #inst "2025-01-04"
             {:group "test.clojars"
              :name "testing2"
              :version "1.0-SNAPSHOT"
              :description "yes"})

    ;; When: we export the poms
    (export-poms/export-all-poms help/*db* temp-repo)

    ;; Then: we have the correct updated at and content for the pom files
    (let [f (io/file temp-repo "test/clojars/testing/1.0/testing-1.0.pom")]
      (is (= (.getTime #inst "2025-01-01") (.lastModified f)))
      (is (match? {:group "test.clojars"
                   :name "testing"
                   :version "1.0"
                   :description "testing"
                   :packaging :jar}
                  (maven/pom-to-map f))))

    (let [f (io/file temp-repo "test/clojars/testing/1.1/testing-1.1.pom")]
      (is (= (.getTime #inst "2025-01-02") (.lastModified f)))
      (is (match? {:group "test.clojars"
                   :name "testing"
                   :version "1.1"
                   :description "testing again"
                   :packaging :pom}
                  (maven/pom-to-map f))))

    (let [f (io/file temp-repo "test/clojars/testing2/1.0-SNAPSHOT/testing2-1.0-SNAPSHOT.pom")]
      (is (= (.getTime #inst "2025-01-04") (.lastModified f)))
      (is (match? {:group "test.clojars"
                   :name "testing2"
                   :version "1.0-SNAPSHOT"
                   :description "yes"
                   :packaging :jar}
                  (maven/pom-to-map f))))))
