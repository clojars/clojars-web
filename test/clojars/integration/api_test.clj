(ns clojars.integration.api-test
  (:require [cheshire.core :as json]
            [clj-http.client :as client]
            [clojars.integration.steps :refer [register-as inject-artifacts-into-repo!]]
            [clojars.test-helper :as help]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kerodon.core :refer [session]]))

(use-fixtures :each
  help/default-fixture
  help/with-clean-database
  help/run-test-app)

(defn get-api [parts & [opts]]
  (-> (str "http://localhost:" help/test-port "/api/"
           (str/join "/" (map name parts)))
      (client/get opts)))

(defn assert-404 [& get-args]
  (try
    (let [resp (apply get-api get-args)]
      ;; this will never succeed, but gives a better error message
      ;; when it fails
      (is (= 404 (:status resp))))
    (catch clojure.lang.ExceptionInfo e
      (is (= 404 (-> e ex-data :status))))))

(deftest utils-test
  (is (= (help/get-content-type {:headers {"content-type" "application/json"}}) "application/json"))
  (is (= (help/get-content-type {:headers {"content-type" "application/json;charset=utf-8"}}) "application/json")))

(deftest an-api-test
  (-> (session (help/app-from-system))
      (register-as "dantheman" "test@example.org" "password"))
  (inject-artifacts-into-repo! (get-in help/system [:db :spec]) "dantheman" "test.jar" "test-0.0.1/test.pom")
  (inject-artifacts-into-repo! (get-in help/system [:db :spec]) "dantheman" "test.jar" "test-0.0.2/test.pom")
  (inject-artifacts-into-repo! (get-in help/system [:db :spec]) "dantheman" "test.jar" "test-0.0.3-SNAPSHOT/test.pom")
  (inject-artifacts-into-repo! (get-in help/system [:db :spec]) "dantheman" "test.jar" "test-0.0.3-SNAPSHOT/test.pom")

  (doseq [f ["application/json" "application/edn" "application/x-yaml" "application/transit+json"]]
    (testing f
      (is (= f (help/get-content-type (get-api [:groups "org.clojars.dantheman"] {:accept f}))))))

  (testing "default format is json"
    (is (= "application/json" (help/get-content-type (get-api [:groups "org.clojars.dantheman"])))))

  (testing "api endpoints uses permissive cors settings"
    (is (help/assert-cors-header (get-api [:groups "org.clojars.dantheman"]))))

  (testing "list group artifacts"
    (let [resp (get-api [:groups "org.clojars.dantheman"] {:accept :json})
          body (json/parse-string (:body resp) true)]

      (is (= {:latest_version "0.0.3-SNAPSHOT"
              :latest_release "0.0.2"
              :jar_name "test"
              :group_name "org.clojars.dantheman"
              :user "dantheman"
              :description "TEST"
              :homepage "http://example.com"
              :downloads 0}
             (first body)))))

  (testing "get non-existent group"
    (assert-404 [:groups "does-not-exist"]))

  (testing "get artifact"
    (let [resp (get-api [:artifacts "org.clojars.dantheman" "test"] {:accept :json})
          body (json/parse-string (:body resp) true)]
      (is (= {:latest_version "0.0.3-SNAPSHOT"
              :latest_release "0.0.2"
              :jar_name "test"
              :license ""
              :scm_url ""
              :group_name "org.clojars.dantheman"
              :user "dantheman"
              :description "TEST"
              :homepage "http://example.com"
              :downloads 0
              :recent_versions [{:downloads 0 :version "0.0.3-SNAPSHOT"}
                                {:downloads 0 :version "0.0.2"}
                                {:downloads 0 :version "0.0.1"}]}
            body))))

  (testing "get non-existent artifact"
    (assert-404 [:artifacts "does-not-exist"])
    (assert-404 [:artifacts "does-not" "exist"]))

  (testing "get user"
    (let [resp (get-api [:users "dantheman"])
          body (json/parse-string (:body resp) true)]
      (is (= {:groups ["net.clojars.dantheman" "org.clojars.dantheman"]}
             body))))

  (testing "get non-existent user"
    (assert-404 [:users "danethemane"])))
