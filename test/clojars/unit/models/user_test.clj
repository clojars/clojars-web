(ns clojars.unit.models.user-test
  (:require
   [clojars.db :as db]
   [clojars.models.permission :as permission-m]
   [clojars.models.user :as user-m]
   [clojars.test-helper :as help]
   [clojars.time :as time]
   [clojure.test :refer [are deftest is use-fixtures]]
   [matcher-combinators.test])
  (:import
   (java.sql
    Timestamp)))

(use-fixtures :each
  help/with-clean-database)

(deftest added-users-can-be-found
  (let [email "test@example.com"
        name "testuser"
        password "password1234"]
    (user-m/add-user help/*db* email name password)
    (are [x] (match? {:email email
                      :user name}
                     x)
      (user-m/find-user help/*db* name)
      (user-m/find-user-by-user-or-email help/*db* name)
      (user-m/find-user-by-user-or-email help/*db* email))))

(deftest user-does-not-exist
  (is (not (user-m/find-user-by-user-or-email help/*db* "test2@example.com"))))

(deftest added-users-can-be-found-by-password-reset-code-except-when-expired
  (let [email "test@example.com"
        name "testuser"
        password "password1234"]
    (user-m/add-user help/*db* email name password)
    (let [reset-code (user-m/set-password-reset-code! help/*db* "testuser")]
      (is (match? {:email email
                   :user name
                   :password_reset_code reset-code}
                  (user-m/find-user-by-password-reset-code help/*db* reset-code)))
      (time/with-now (time/days-from 2)
        (is (not (user-m/find-user-by-password-reset-code help/*db* reset-code)))))))

(deftest renamed-users-can-be-found
  (let [email "test@example.com"
        name "testuser"
        password "password1234"
        created-at (Timestamp. 0)
        name2 "testuser2"]
    (help/with-time created-at
      (user-m/add-user help/*db* email name password)
      (help/with-time (Timestamp. 1)
        (user-m/rename-user help/*db* name name2)
        (are [x] (match? {:user name2
                          :created created-at}
                         x)
          (user-m/find-user help/*db* name2)
          (user-m/find-user-by-user-or-email help/*db* name2)))
      (is (not (user-m/find-user help/*db* name))))))

(deftest updated-users-can-be-found
  (let [email "test@example.com"
        name "testuser"
        password "password1234"
        created-at (Timestamp. 0)
        email2 "test2@example.com"
        password2 "password1234b"]
    (help/with-time created-at
      (user-m/add-user help/*db* email name password)
      (help/with-time (Timestamp. 1)
        (user-m/update-user help/*db* name email2 password2)
        (is (match?
             {:email email2
              :created created-at}
             (user-m/find-user-by-user-or-email help/*db* email2)))))))

(deftest update-user-works-when-password-is-blank
  (let [email "test@example.com"
        name "testuser"
        password "password1234"
        email2 "test2@example.com"
        password2 ""]
    (user-m/add-user help/*db* email name password)
    (let [old-user (user-m/find-user help/*db* name)]
      (user-m/update-user help/*db* name email2 password2)
      (let [user (user-m/find-user help/*db* name)]
        (is (= email2 (:email user)))
        (is (= (:password old-user) (:password user)))))))

(deftest added-users-are-added-only-to-their-net-and-org-clojars-group-as-admins-and-those-groups-are-verified
  (let [email "test@example.com"
        name "testuser"
        password "password1234"]
    (user-m/add-user help/*db* email name password)
    (is (= ["testuser"] (permission-m/group-adminnames help/*db* (str "org.clojars." name) permission-m/SCOPE-ALL)))
    (is (= ["testuser"] (permission-m/group-activenames help/*db* (str "org.clojars." name))))
    (is (= [] (permission-m/group-membernames help/*db* (str "org.clojars." name)  permission-m/SCOPE-ALL)))
    (is (= ["testuser"] (permission-m/group-adminnames help/*db* (str "net.clojars." name) permission-m/SCOPE-ALL)))
    (is (= ["testuser"] (permission-m/group-activenames help/*db* (str "net.clojars." name))))
    (is (= [] (permission-m/group-membernames help/*db* (str "net.clojars." name) permission-m/SCOPE-ALL)))
    (is (= ["net.clojars.testuser" "org.clojars.testuser"]
           (permission-m/find-groupnames help/*db* name)))))

(deftest email-verification-code-functions
  (let [email "test@example.com"
        name  "testuser"]
    (user-m/add-user help/*db* email name "password1234")
    ;; Initially email is not verified
    (is (match? {:email_verified false
                 :email_verified_at nil
                 :email_verification_code nil}
                (user-m/find-user help/*db* name)))
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
                  (user-m/find-user help/*db* name)))
      ;; Code is no longer valid
      (is (nil? (user-m/find-user-by-email-verification-code help/*db* code))))))

(deftest email-verification-code-expiry
  (let [email "test@example.com"
        name  "testuser"]
    (user-m/add-user help/*db* email name "password1234")
    (let [code (user-m/set-email-verification-code help/*db* name)]
      (is (some? (user-m/find-user-by-email-verification-code help/*db* code)))
      (time/with-now (time/days-from 2)
        (is (nil? (user-m/find-user-by-email-verification-code help/*db* code)))))))

(deftest clear-email-verification
  (let [email "test@example.com"
        name  "testuser"
        ts    (db/get-time)]
    (user-m/add-user help/*db* email name "password1234")
    (with-redefs [db/get-time (constantly ts)]
      (user-m/set-email-verification-code help/*db* name))
    (is (match? {:email_verification_code some?
                 :email_verification_code_created_at ts}
                (user-m/find-user help/*db* name)))
    (user-m/clear-email-verification help/*db* name)
    (is (match? {:email_verification_code nil
                 :email_verification_code_created_at nil}
                (user-m/find-user help/*db* name)))))
