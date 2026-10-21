(ns clojars.unit.hibp-test
  (:require
   [clojars.hibp :as hibp]
   [clojars.http-client :as http]
   [clojure.test :refer [deftest is testing]]))

;; SHA-1 of "password" is 5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8
(def ^:private password-suffix #_"5BAA6" "1E4C9B93F3F0682250B6CF8331B7EE68FD8")

(defn- range-body
  [& lines]
  (apply str (interpose "\r\n" lines)))

(defn- check
  "Calls `pwned?` for `password` with http requests answered by `response-fn`.
  Returns [result requests]."
  [response-fn password]
  (let [requests (atom [])]
    (with-redefs [http/request (fn [opts]
                                 (swap! requests conj opts)
                                 (response-fn opts))]
      [(hibp/pwned? (hibp/new-hibp) password) @requests])))

(defn- pwned-with-response
  [response]
  (first (check (constantly response) "password")))

(deftest pwned?-only-sends-hash-prefix
  (let [[_ [{:keys [url headers]}]]
        (check (constantly {:status 200
                            :body   (range-body "0018A45C4D1DEF81644B54AB7F969B88D65:1")})
               "password")]
    (is (= "https://api.pwnedpasswords.com/range/5BAA6" url))
    (is (= "true" (get headers "Add-Padding")))))

(deftest pwned?-matches-suffix-with-count
  (testing "suffix present with a count"
    (is (true? (pwned-with-response
                {:status 200
                 :body   (range-body "0018A45C4D1DEF81644B54AB7F969B88D65:1"
                                     (str password-suffix ":10434004"))}))))

  (testing "suffix absent"
    (is (false? (pwned-with-response
                 {:status 200
                  :body   (range-body "0018A45C4D1DEF81644B54AB7F969B88D65:1")}))))

  (testing "padding entries have a zero count"
    (is (false? (pwned-with-response
                 {:status 200
                  :body   (range-body (str password-suffix ":0"))})))))

(deftest pwned?-fails-open
  (testing "non-200 response"
    (is (false? (pwned-with-response {:status 503 :body "unavailable"}))))

  (testing "connection error"
    (is (false? (pwned-with-response {:error (Exception. "connection refused")}))))

  (testing "exception"
    (is (false? (first (check (fn [_] (throw (ex-info "HTTP request timeout" {})))
                              "password"))))))
