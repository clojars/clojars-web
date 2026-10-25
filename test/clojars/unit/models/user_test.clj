(ns clojars.unit.models.user-test
  (:require
   [clojars.db :as db]
   [clojars.models.audit :as audit-m]
   [clojars.models.deploy-token :as deploy-token-m]
   [clojars.models.group-verification :as group-verification-m]
   [clojars.models.jar :as jar-m]
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
      (user-m/find-user-by-username|merged-usernames help/*db* name)
      (user-m/find-user-by-user-or-email help/*db* name)
      (user-m/find-user-by-user-or-email help/*db* email))))

(deftest merged-users-can-be-found
  (let [email "test@example.com"
        name "testuser"
        password "password1234"]
    (user-m/add-user help/*db* email name password)
    (user-m/append-merged-usernames help/*db* name ["testuser2"])
    (is (match?
         {:email email
          :user name}
         (user-m/find-user-by-username|merged-usernames help/*db* "testuser2")))))

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


(deftest merge-users
  (let [username1 "user1"
        username2 "user2"
        username3 "user3"
        user1 (user-m/add-user help/*db* "user1@example.com" username1 "password1234")
        _user2 (user-m/add-user help/*db* "user2@example.com" username2 "password1234")
        user3 (user-m/add-user help/*db* "user3@example.com" username3 "password1234")]
    (user-m/append-merged-usernames help/*db* username3 ["a-prior-merge"])
    (deploy-token-m/add-deploy-token help/*db* (:id user3) "a-token" nil nil false nil)
    (jar-m/add-jar help/*db* username2 {:group (format "org.clojars.%s" username2)
                                        :name "testing"
                                        :version "1.0"})
    (group-verification-m/verify-group! help/*db* username2 "com.example")
    (audit-m/add-audit help/*db* "testing" username3 nil nil nil nil)
    (user-m/merge-users! help/*db* username1 [username2 username3])
    (is (match? {:merged_usernames [username2 username3 "a-prior-merge"]}
                (user-m/find-user help/*db* username1)))
    (is (= (reduce
            (fn [acc username]
              (conj acc
                    (format "net.clojars.%s" username)
                    (format "org.clojars.%s" username)))
            #{}
            [username1 username2 username3])
           (set (permission-m/find-groupnames help/*db* username1))))
    (is (match? {:user username1}
                (jar-m/find-jar help/*db* (format "org.clojars.%s" username2) "testing")))
    (is (match? {:verified_by username1}
                (group-verification-m/find-group-verification help/*db* "com.example")))
    (is (match? [{:tag "testing"}]
                (audit-m/find-audit help/*db* {:username username1})))
    (is (match? [{:name "a-token"}]
                (deploy-token-m/find-deploy-tokens-for-user help/*db* (:id user1))))
    (is (nil? (user-m/find-user help/*db* username2)))
    (is (nil? (user-m/find-user help/*db* username3)))))

(deftest merge-users-with-non-existent-main-user-throws
  (let [username1 "user1"]
    (user-m/add-user help/*db* "user1@example.com" username1 "password1234")
    (is (thrown-with-msg? Exception #"User does not exist"
          (user-m/merge-users! help/*db* "non-user" [username1])))))

(deftest merge-users-with-non-existent-merge-user-throws
  (let [username1 "user1"]
    (user-m/add-user help/*db* "user1@example.com" username1 "password1234")
    (is (thrown-with-msg? Exception #"User does not exist"
          (user-m/merge-users! help/*db* username1 ["non-user"])))))

(deftest merge-users-with-user-already-in-group-does-the-right-thing
  (let [username1 "user1"
        username2 "user2"]
    (user-m/add-user help/*db* "user1@example.com" username1 "password1234")
    (user-m/add-user help/*db* "user2@example.com" username2 "password1234")
    ;; both users in same group, one as admin
    (permission-m/add-group help/*db* "testing" "com.example")
    (permission-m/add-admin help/*db* "com.example"
                            permission-m/SCOPE-ALL username2 "testing")
    (permission-m/add-member help/*db* "com.example"
                             permission-m/SCOPE-ALL username1 "testing")
    ;; both users in same group, both admins, but one has narrower scope
    (permission-m/add-group help/*db* "testing" "org.example")
    (permission-m/add-admin help/*db* "org.example"
                            permission-m/SCOPE-ALL username1 "testing")
    (permission-m/add-admin help/*db* "org.example"
                            "a-project" username2 "testing")
    ;; both users in same group, both not admins, but one has narrower scope
    (permission-m/add-group help/*db* "testing" "org.example2")
    (permission-m/add-member help/*db* "org.example2"
                             permission-m/SCOPE-ALL username1 "testing")
    (permission-m/add-member help/*db* "org.example2"
                             "a-project" username2 "testing")
    ;; both users in same group, both not admins, with both having project scope
    ;; Note: this will result in both memberships being kept
    (permission-m/add-group help/*db* "testing" "org.example3")
    (permission-m/add-member help/*db* "org.example3"
                             "some-project" username1 "testing")
    (permission-m/add-member help/*db* "org.example3"
                             "a-project" username2 "testing")
    (user-m/merge-users! help/*db* username1 [username2])
    (is (match?
         [{:group_name "com.example"
           :admin true
           :scope "*"}
          {:group_name "net.clojars.user1"
           :admin true
           :scope "*"}
          {:group_name "net.clojars.user2"
           :admin true
           :scope "*"}
          {:group_name "org.clojars.user1"
           :admin true
           :scope "*"}
          {:group_name "org.clojars.user2"
           :admin true
           :scope "*"}
          {:group_name "org.example"
           :admin true
           :scope "*"}
          {:group_name "org.example2"
           :admin false
           :scope "*"}
          {:group_name "org.example3"
           :admin false
           :scope "a-project"}
          {:group_name "org.example3"
           :admin false
           :scope "some-project"}]
         (sort-by (juxt :group_name :scope)
                  (permission-m/find-memberships-for-users
                   help/*db* [username1 username2]))))))
