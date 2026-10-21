(ns clojars.unit.hibp-test
  (:require
   [clojars.hibp :as hibp]
   [clojars.remote-service :as remote-service]
   [clojure.test :refer [deftest is testing]]))

;; SHA-1 of "password" is 5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8
(def ^:private password-suffix #_"5BAA6" "1E4C9B93F3F0682250B6CF8331B7EE68FD8")

(defn- range-body
  [& lines]
  (apply str (interpose "\r\n" lines)))

(defn- pwned-with-response
  [responder]
  (remote-service/with-mocking
    (remote-service/set-responder '-password-range responder)
    (hibp/pwned? (remote-service/new-mock-remote-service) "password")))

(deftest pwned?-only-sends-hash-prefix
  (remote-service/with-mocking
    (hibp/pwned? (remote-service/new-mock-remote-service) "password")
    (let [[{:keys [url headers response-type]}] (remote-service/get-calls)]
      (is (= "https://api.pwnedpasswords.com/range/5BAA6" url))
      (is (= "true" (get headers "Add-Padding")))
      (is (= :text response-type)))))

(deftest pwned?-matches-suffix
  (testing "suffix present"
    (is (true? (pwned-with-response
                (constantly (range-body "0018A45C4D1DEF81644B54AB7F969B88D65:1"
                                        (str password-suffix ":10434004")))))))

  (testing "suffix absent"
    (is (false? (pwned-with-response
                 (constantly (range-body "0018A45C4D1DEF81644B54AB7F969B88D65:1")))))))

(deftest pwned?-fails-open
  (is (false? (pwned-with-response
               (fn [_] (throw (ex-info "HTTP request failed" {:response {:status 503}})))))))
