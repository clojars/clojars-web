(ns clojars.integration.search-test
  (:require
   [cheshire.core :as json]
   [clj-http.client :as client]
   [clojars.search :as search]
   [clojars.test-helper :as help]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clojure.xml :as xml]
   [matcher-combinators.test])
  (:import
   (java.util
    Date)))

(use-fixtures :each
  help/default-fixture
  help/run-test-app)

(defn do-search [fmt query & [opts]]
  (-> (format "http://localhost:%s/search?q=%s&format=%s"
              help/test-port
              query
              (name fmt))
      (client/get opts)))

(defn do-search-with-page [fmt query page & [opts]]
  (-> (format "http://localhost:%s/search?q=%s&format=%s&page=%s"
              help/test-port
              query
              (name fmt)
              page)
      (client/get opts)))

(defn created-as-str
  [data]
  (update data :created #(str (.getTime %))))

(deftest search-test
  (let [base-data {:description "foo" :version "1.0"}
        search-data (map (partial merge base-data)
                         [{:created (Date.)
                           :group_name "test"
                           :jar_name "test"}
                          {:created (Date.)
                           :group_name "testing"
                           :jar_name "test"}])]
    (doseq [data search-data]
      (search/index! (:search help/system) data))

    (testing "json request returns json"
      (let [resp (do-search :json "test")]
        (is (= 200 (:status resp)))
        (is (= "application/json" (help/get-content-type resp)))))

    (testing "json request uses permissive cors headers"
      (is (help/assert-cors-header (do-search :json "test"))))

    (testing "xml request returns xml"
      (let [resp (do-search :xml "test")
            parsed (xml/parse (java.io.ByteArrayInputStream.
                               (.getBytes (:body resp))))]
        (is (= 200 (:status resp)))
        (is (= "text/xml" (help/get-content-type resp)))
        (is (map? parsed))
        (is (= "2" (:count (:attrs parsed))))
        (is (= 2 (count (:content parsed))))))

    (testing "default request returns html"
      (let [resp (do-search "" "test")]
        (is (= 200 (:status resp)))
        (is (= "text/html" (help/get-content-type resp)))))

    (testing "request with valid page returns data"
      (let [resp (do-search-with-page "" "test" "1" {:throw-exceptions false})]
        (is (= 200 (:status resp)))
        (is (= "text/html" (help/get-content-type resp)))))

    (testing "request with invalid page returns error"
      (let [resp (do-search-with-page "" "test" "a" {:throw-exceptions false})]
        (is (= 400 (:status resp)))
        (is (= "text/html" (help/get-content-type resp)))))

    (testing "unknown format request returns html" ;; debatable behavior
      (let [resp (do-search :ham "test")]
        (is (= 200 (:status resp)))
        (is (= "text/html" (help/get-content-type resp)))))

    (testing "valid request returns data"
      (let [resp (do-search :json "test")
            result (json/parse-string (:body resp) true)]
        (is (= 200 (:status resp)))
        (is (= 2 (:count result)))
        (is (match? (mapv created-as-str search-data) (:results result)))))

    (testing "json request with invalid page returns error"
      (let [resp (do-search-with-page :json "test" "a" {:throw-exceptions false})
            result (json/parse-string (:body resp) true)]
        (is (= 400 (:status resp)))
        (is (= "application/json" (help/get-content-type resp)))
        (is (not (nil? (:error result))))
        (is (not (nil? (:error-id result))))))

    (testing "invalid query syntax returns error"
      (let [resp (do-search :json "test+AND" {:throw-exceptions false})
            result (json/parse-string (:body resp) true)]
        (is (= 400 (:status resp)))
        (is (nil? (:count result)))
        (is (nil? (:results result)))
        (is (= "Invalid search syntax for query `test AND`" (:error result)))))))
