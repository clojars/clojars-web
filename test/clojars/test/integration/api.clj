(ns clojars.test.integration.api
  (:require [clj-http.lite.client :as client]
            [clojars.test.integration.steps :refer [register-as scp valid-ssh-key]]
            [clojars.test.test-helper :as help]
            [clojars.web :as web]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [kerodon.core :refer [session]]
            [clojure.string :as string]
            [cheshire.core :as json]))

(use-fixtures :once help/run-test-app #_(partial help/run-test-app :verbose))
(use-fixtures :each help/default-fixture)

(defn get-api [parts & [opts]]
  (-> (str "http://localhost:" help/test-port "/api/"
           (str/join "/" (map name parts)))
      (client/get opts)))

(defn get-content-type [resp]
  (some-> resp :headers (get "content-type") (string/split #";") first))

(deftest utils-test
  (is (= (get-content-type {:headers {"content-type" "application/json"}}) "application/json"))
  (is (= (get-content-type {:headers {"content-type" "application/json;charset=utf-8"}}) "application/json")))

(deftest an-api-test
  (-> (session web/clojars-app)
      (register-as "dantheman" "test@example.org" "password" valid-ssh-key))
  (scp valid-ssh-key "test.jar" "test-0.0.1/test.pom")
  (scp valid-ssh-key "test.jar" "test-0.0.2/test.pom")
  (scp valid-ssh-key "test.jar" "test-0.0.3-SNAPSHOT/test.pom")
  (scp valid-ssh-key "test.jar" "test-0.0.3-SNAPSHOT/test.pom")

  (doseq [f ["application/json" "application/edn" "application/x-yaml" "application/transit+json"]]
    (testing f
      (is (= f (get-content-type (get-api [:groups "fake"] {:accept f}))))))

  (testing "default format is json"
    (is (= "application/json" (get-content-type (get-api [:groups "fake"])))))

  (testing "list group artifacts"
    (let [resp (get-api [:groups "fake"] {:accept :json})
          body (json/parse-string (:body resp) true)]
      (is (= {:latest_version "0.0.3-SNAPSHOT"
              :latest_release "0.0.2"
              :jar_name "test"
              :group_name "fake"
              :user "dantheman"}
             (select-keys (first body) [:latest_release :latest_version :jar_name :group_name :user])))))

  (testing "get artifact"
    (let [resp (get-api [:artifacts "fake" "test"] {:accept :json})
          body (json/parse-string (:body resp) true)]
      (is (= {:version "0.0.2"
              :jar_name "test"
              :group_name "fake"
              :user "dantheman"
              :recent_versions [{:downloads 0 :version "0.0.3-SNAPSHOT"}
                                {:downloads 0 :version "0.0.2"}
                                {:downloads 0 :version "0.0.1"}]}
             (select-keys body [:jar_name :group_name :version :recent_versions :user])))))

  (testing "get user"
    (let [resp (get-api [:users "dantheman"])
          body (json/parse-string (:body resp) true)]
      (is (= {:groups ["org.clojars.dantheman" "fake"]}
             body)))))
