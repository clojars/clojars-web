(ns clojars.test.unit.tools.generate-feed
  (:require [clojars.tools.generate-feed :refer :all]
            [clojars.config :refer [config]]
            [clojars.db :as db]
            [clojure.test :refer :all]
            [clojars.test.test-helper :as help]
            [clojure.java.io :as io]
            [clojars.maven :as maven]))

(use-fixtures :each
              help/default-fixture
              help/with-clean-database)

(defn setup-db []
  (doseq [name ["fake" "test"]
          version ["0.0.1" "0.0.2" "0.0.3-SNAPSHOT"]
          :let [pom-data (maven/pom-to-map (io/resource (format "%s-%s/%s.pom" name version name)))]]
    (db/add-jar help/*db* "testuser" pom-data)))

(deftest feed-generation-should-work
  (setup-db)
  (let [expected [{:description "FAKE"
                   :group-id    "fake"
                   :artifact-id "fake"
                   :versions    ["0.0.3-SNAPSHOT" "0.0.2" "0.0.1"]}

                  {:description "TEST"
                   :scm         {:connection           "scm:git:git://github.com/fake/test.git"
                                 :developer-connection "scm:git:ssh://git@github.com/fake/test.git"
                                 :tag                  "70470ff6ae74505bdbfe5955fca6797f613c113c"
                                 :url                  "https://github.com/fake/test"}
                   :group-id    "fake"
                   :artifact-id "test"
                   :url         "http://example.com"
                   :homepage    "http://example.com"
                   :versions    ["0.0.3-SNAPSHOT" "0.0.2" "0.0.1"]}]]
    (is (= expected (full-feed help/*db*)))))
