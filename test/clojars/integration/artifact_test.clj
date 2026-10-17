(ns clojars.integration.artifact-test
  (:require
   [clojars.http-client :as http]
   [clojars.test-helper :as help]
   [clojure.string :as str]
   [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :each
  help/default-fixture
  help/with-clean-database
  help/run-test-app)

(defn get-artifact
  [parts & [param-string]]
  (http/get
   (format "http://localhost:%s/%s%s"
           help/test-port
           (str/join "/" (map name parts))
           (if param-string
             (format "?%s" param-string)
             ""))))

(deftest latest-version-should-have-permissive-cors-headers
  (doseq [suffix ["json" "svg"]]
    (is (help/assert-cors-header (get-artifact ["test" (format "latest-version.%s" suffix)])))))

(deftest latest-version-routes-should-allow-numeric-param
  (doseq [suffix ["json" "svg"]]
    (is (= 200 (:status (get-artifact ["test" (format "latest-version.%s" suffix)] "12345"))))))
