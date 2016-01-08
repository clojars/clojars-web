(ns clojars.test.unit.tools.generate-feed
  (:require [clojars.tools.generate-feed :refer :all]
            [clojars.config :refer [config]]
            [clojure.test :refer :all]
            [clojars.test.test-helper :as help]
            [clojure.java.io :as io]))

(use-fixtures :each help/default-fixture)

(defn setup-repo []
  (doseq [name ["fake" "test"]
          version ["0.0.1" "0.0.2" "0.0.3-SNAPSHOT"]
          :let [dest-file (io/file (:repo config)
                                   (format "%s/%s/%s/%s-%s.pom" name name version name version))]]
    (.mkdirs (.getParentFile dest-file))
    (io/copy (io/reader (io/resource (format "%s-%s/%s.pom" name version name)))
             dest-file)))

(deftest feed-generation-should-work
  (setup-repo)
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
    (is (= expected (full-feed (io/file (:repo config)))))))
