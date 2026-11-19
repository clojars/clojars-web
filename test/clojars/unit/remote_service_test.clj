(ns clojars.unit.remote-service-test
  (:require
   [clojars.http-client :as http]
   [clojars.remote-service :as remote-service]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [clojure.tools.logging.test :as log-test]))

(defn- call-with-response
  [response request-info]
  (with-redefs [http/request (constantly response)]
    (remote-service/-remote-call (remote-service/new-http-remote-service) request-info)))

(deftest response-body-is-parsed-as-json-by-default
  (is (= {:a 1} (call-with-response {:status 200 :body "{\"a\": 1}"}
                                    {:url "https://example.com"}))))

(deftest response-body-can-be-returned-as-text
  (is (= "ABC:1" (call-with-response {:status 200 :body "ABC:1"}
                                     {:url "https://example.com" :response-type :text}))))

(deftest connection-errors-are-request-failures
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"HTTP request failed"
                        (call-with-response {:error (Exception. "connection refused")}
                                            {:url "https://example.com"}))))

(deftest url-pattern-is-logged-instead-of-url
  (log-test/with-log
    (try
      (call-with-response {:status 500 :opts {:url "https://example.com/range/ABCDE"}}
                          {:url "https://example.com/range/ABCDE"
                           :url-pattern "https://example.com/range/{prefix}"})
      (catch clojure.lang.ExceptionInfo _))
    (let [logged (str/join "\n" (map :message (log-test/the-log)))]
      (is (str/includes? logged "https://example.com/range/{prefix}"))
      (is (not (str/includes? logged "ABCDE"))))))
