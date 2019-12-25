(ns clojars.integration.artifact-test
  (:require [clj-http.client :as client]
            [clojars.test-helper :as help]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each
              help/using-test-config
              help/default-fixture
              help/with-clean-database
              help/run-test-app)

(defn get-artifact [parts & [opts]]
  (-> (str "http://localhost:" help/test-port "/"
           (str/join "/" (map name parts)))
      (client/get opts)))

(deftest artifacts-test
  (testing "latest-version.json should have permissive cors headers"
    (is (help/assert-cors-header (get-artifact ["test" "latest-version.json"])))))
