(ns clojars.unit.models.email-validation-test
  (:require
   [clojars.models.email-validation :as email-validation-m]
   [clojars.remote-service :as remote-service]
   [clojure.test :refer [deftest is]]))

(deftest address-from-local-list-is-rejected
  (is (false? (email-validation-m/allowed-email? nil "foo@0-mail.com"))))

(deftest address-that-is-disposable-from-user-check-service-is-rejected
  (remote-service/with-mocking
    (remote-service/set-responder
     'check-email
     (constantly {:disposable true}))
    (is (false? (email-validation-m/allowed-email?
                 (remote-service/new-mock-remote-service)
                 "contact@clojars.org")))))

(deftest address-that-is-not-disposable-from-user-check-service-is-allowed
  (remote-service/with-mocking
    (remote-service/set-responder
     'check-email
     (constantly {:disposable false}))
    (is (true? (email-validation-m/allowed-email?
                (remote-service/new-mock-remote-service)
                "contact@clojars.org")))))

(deftest check-when-user-check-service-throws-is-allowed
  (remote-service/with-mocking
    (remote-service/set-responder
     'check-email
     (fn [& _] (throw (Exception. "boom"))))
    (is (true? (email-validation-m/allowed-email?
                (remote-service/new-mock-remote-service)
                "contact@clojars.org")))))
