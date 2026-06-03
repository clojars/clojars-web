(ns clojars.unit.hibp-test
  (:require
   [clj-http.client]
   [clojars.hibp :as hibp]
   [clojure.test :refer [deftest is testing]]
   [matcher-combinators.test])
  (:import
   (java.net SocketTimeoutException)))

(deftest mock-hibp-detects-pwned-passwords
  (let [client (hibp/new-mock-hibp (atom {"hunter2" 12345}))]
    (is (true? (hibp/pwned? client "hunter2")))
    (is (false? (hibp/pwned? client "something-else")))))

(deftest pwned?-handles-blank-passwords
  (let [client (hibp/new-mock-hibp (atom {"" 1}))]
    (is (false? (hibp/pwned? client "")))
    (is (false? (hibp/pwned? client nil)))))

(deftest pwned?-fails-open-on-network-error
  (let [client (reify hibp/Hibp
                 (-pwned-count [_ _]
                   (throw (SocketTimeoutException. "boom"))))]
    (is (false? (hibp/pwned? client "any-password")))))

(deftest pwned?-fails-open-on-other-errors
  (let [client (reify hibp/Hibp
                 (-pwned-count [_ _]
                   (throw (ex-info "unexpected" {}))))]
    (is (false? (hibp/pwned? client "any-password")))))

(deftest http-hibp-parses-range-response
  ;; "password" SHA-1 = 5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8
  ;; Prefix: 5BAA6, Suffix: 1E4C9B93F3F0682250B6CF8331B7EE68FD8
  (let [http-hibp (hibp/->HttpHibp)]
    (testing "parse-range-response is package-private; assert via public protocol behaviour by stubbing http"
      (with-redefs [clj-http.client/get
                    (fn [_url _opts]
                      {:status 200
                       :body (str "0018A45C4D1DEF81644B54AB7F969B88D65:1\r\n"
                                  "1E4C9B93F3F0682250B6CF8331B7EE68FD8:1234\r\n"
                                  "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF:7\r\n")})]
        (is (= 1234 (hibp/-pwned-count http-hibp "password")))))
    (testing "returns 0 when suffix not present"
      (with-redefs [clj-http.client/get
                    (fn [_url _opts]
                      {:status 200
                       :body "0018A45C4D1DEF81644B54AB7F969B88D65:1\r\n"})]
        (is (= 0 (hibp/-pwned-count http-hibp "password")))))
    (testing "throws on non-200 so caller can fail open"
      (with-redefs [clj-http.client/get
                    (fn [_url _opts]
                      {:status 503 :body ""})]
        (is (thrown? clojure.lang.ExceptionInfo
                     (hibp/-pwned-count http-hibp "password")))))))
