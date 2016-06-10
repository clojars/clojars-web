(ns clojars.test.integration.artifact
  (:require [clojure.test :refer :all]
            [clojars.test.test-helper :as help]
            [clojure.string :as str]
            [clj-http.client :as client]
            [kerodon.core :refer [session]]
            [clojars.test.integration.steps :refer [register-as inject-artifacts-into-repo!]]
            [cheshire.core :as json]))

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
