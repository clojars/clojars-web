(ns clojars.unit.models.user-test
  (:require
   [clojars.db :as db]
   [clojars.models.user :as user-m]
   [clojars.test-helper :as help]
   [clojars.time :as time]
   [clojure.test :refer [deftest is use-fixtures]]
   [matcher-combinators.test]))

(use-fixtures :each
  help/with-clean-database)

(deftest email-verification-code-functions
  (let [email "test@example.com"
        name  "testuser"]
    (db/add-user help/*db* email name "password1234")
    ;; Initially email is not verified
    (is (match? {:email_verified false
                 :email_verified_at nil
                 :email_verification_code nil}
                (db/find-user help/*db* name)))
    ;; Set a verification code
    (let [ts (db/get-time)
          code (with-redefs [db/get-time (constantly ts)]
                 (user-m/set-email-verification-code help/*db* name))]
      (is (string? code))
      (is (= 40 (count code))) ;; 20 bytes hex = 40 chars
      ;; Can find the user by code
      (is (match? {:user name
                   :email_verification_code code
                   :email_verification_code_created_at ts}
                  (user-m/find-user-by-email-verification-code help/*db* code)))
      ;; Mark email verified
      (with-redefs [db/get-time (constantly ts)]
        (user-m/mark-email-verified help/*db* name))
      (is (match? {:email_verified true
                   :email_verified_at ts
                   :email_verification_code nil
                   :email_verification_code_created_at nil}
                  (db/find-user help/*db* name)))
      ;; Code is no longer valid
      (is (nil? (user-m/find-user-by-email-verification-code help/*db* code))))))

(deftest email-verification-code-expiry
  (let [email "test@example.com"
        name  "testuser"]
    (db/add-user help/*db* email name "password1234")
    (let [code (user-m/set-email-verification-code help/*db* name)]
      (is (some? (user-m/find-user-by-email-verification-code help/*db* code)))
      (time/with-now (time/days-from 2)
        (is (nil? (user-m/find-user-by-email-verification-code help/*db* code)))))))

(deftest clear-email-verification
  (let [email "test@example.com"
        name  "testuser"
        ts    (db/get-time)]
    (db/add-user help/*db* email name "password1234")
    (with-redefs [db/get-time (constantly ts)]
      (user-m/set-email-verification-code help/*db* name))
    (is (match? {:email_verification_code some?
                 :email_verification_code_created_at ts}
                (db/find-user help/*db* name)))
    (user-m/clear-email-verification help/*db* name)
    (is (match? {:email_verification_code nil
                 :email_verification_code_created_at nil}
                (db/find-user help/*db* name)))))
