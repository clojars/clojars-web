(ns clojars.integration.artifact-test
  (:require
   [cheshire.core :as json]
   [clojars.http-client :as http]
   [clojars.models.group-verification :as group-verification-m]
   [clojars.models.jar :as jar-m]
   [clojars.models.permission :as permission-m]
   [clojars.test-helper :as help]
   [clojure.data.xml :as xml]
   [clojure.string :as str]
   [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :each
  help/default-fixture
  help/with-clean-database
  help/run-test-app)

(defn- add-test-jar
  []
  (permission-m/add-group help/*db* "testuser" "test")
  (group-verification-m/verify-group! help/*db* "testuser" "test")
  (jar-m/add-jar help/*db* "testuser" {:group "test" :name "test" :version "1.0.0"}))

(defn- get-artifact
  [parts & [param-string]]
  (http/get
   (format "http://localhost:%s/%s%s"
           help/test-port
           (str/join "/" (map name parts))
           (if param-string
             (format "?%s" param-string)
             ""))))

(deftest latest-version-routes-return-404-for-non-existent-jar
  (doseq [suffix ["json" "svg"]]
    (is (= 404 (:status (get-artifact ["test" (format "latest-version.%s" suffix)]))))))

(deftest latest-version-should-have-permissive-cors-headers
  (add-test-jar)
  (doseq [suffix ["json" "svg"]]
    (is (help/assert-cors-header (get-artifact ["test" (format "latest-version.%s" suffix)])))))

(deftest latest-version-routes-should-allow-numeric-param
  (add-test-jar)
  (doseq [suffix ["json" "svg"]]
    (is (= 200 (:status (get-artifact ["test" (format "latest-version.%s" suffix)] "12345"))))))

(deftest latest-version-svg-should-return-valid-xml
  (add-test-jar)
  (let [{:keys [body status]} (get-artifact ["test" "latest-version.svg"])]
    (is (= 200 status))
    (try
      (is (= "\"1.0.0\"" (-> (xml/parse body)
                             :content
                             (nth 2)
                             :content
                             (nth 3)
                             :content
                             first)))
      (catch Exception e
        (is false "xml parsing threw")
        (throw e)))))

(deftest latest-version-svg-should-return-valid-json
  (add-test-jar)
  (let [{:keys [body status]} (get-artifact ["test" "latest-version.json"])]
    (is (= 200 status))
    (try
      (is (= {:version "1.0.0"} (json/decode body true)))
      (catch Exception e
        (is false "json parsing threw")
        (throw e)))))
