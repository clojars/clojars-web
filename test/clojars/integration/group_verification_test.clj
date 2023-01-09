(ns clojars.integration.group-verification-test
  (:require
   [clj-http.client :as http]
   [clojars.email :as email]
   [clojars.integration.steps :refer [register-as]]
   [clojars.test-helper :as help]
   [clojure.test :refer [deftest is use-fixtures]]
   [kerodon.core :as kerodon :refer [fill-in follow-redirect
                                     press visit within]]
   [kerodon.test :refer [has some-text?]]))

(use-fixtures :each
  help/with-test-system*)

(defn session
  []
  (let [session (-> (kerodon/session (help/app-from-system))
                    (register-as "dantheman" "test@example.org" "password"))]
    (email/expect-mock-emails 1)
    session))

(defn assert-admin-email
  [state]
  (email/wait-for-mock-emails)
  (let [emails @email/mock-emails
        [[address title]] emails]
    (is (= 1 (count emails)))
    (is (= "contact@clojars.org" address))
    (is (= (format "[Clojars] Group verification attempt %s"
                   (if (= :success state) "succeeded" "failed"))
           title))))

(deftest user-can-verify-group
  (help/with-TXT ["clojars dantheman"]
    (-> (session)
        (visit "/verify/group")
        (within [:div.via-txt]
          (fill-in "Group name" "com.example")
          (fill-in "Domain with TXT record" "example.com")
          (press "Verify Group"))
        (follow-redirect)
        (within [:div.info]
          (has (some-text? "The group 'com.example' has been verified"))))
    (assert-admin-email :success)))

(deftest user-cannot-verify-non-corresponding-group
  (help/with-TXT ["clojars dantheman"]
    (-> (session)
        (visit "/verify/group")
        (within [:div.via-txt]
          (fill-in "Group name" "com.example")
          (fill-in "Domain with TXT record" "example.org")
          (press "Verify Group"))
        (follow-redirect)
        (within [:div.error]
          (has (some-text? "Group and domain do not correspond with each other"))))
    (assert-admin-email :failure)))

(deftest user-can-verify-sub-group
  (help/with-TXT ["clojars dantheman"]
    (let [sess (-> (session)
                  (visit "/verify/group")
                  (within [:div.via-txt]
                          (fill-in "Group name" "com.example")
                          (fill-in "Domain with TXT record" "example.com")
                          (press "Verify Group"))
                  (follow-redirect)
                  (within [:div.info]
                          (has (some-text? "The group 'com.example' has been verified"))))]
      (assert-admin-email :success)
      (email/expect-mock-emails 1)
      (-> sess
          (within [:div.via-parent]
                  (fill-in "Group name" "com.example.ham")
                  (press "Verify Group"))
          (follow-redirect)
          (within [:div.info]
                  (has (some-text? "The group 'com.example.ham' has been verified"))))
      (assert-admin-email :success))))

(deftest user-cannot-verify-subgroup-with-non-verified-parent
  (-> (session)
      (visit "/verify/group")
      (within [:div.via-parent]
        (fill-in "Group name" "com.example")
        (press "Verify Group"))
      (follow-redirect)
      (within [:div.error]
        (has (some-text? "The group is not a subgroup of a verified group"))))
  (assert-admin-email :failure))

(deftest user-can-verify-vcs-groups
  (with-redefs [http/head (constantly {:status 200})]
    (-> (session)
        (visit "/verify/group")
        (within [:div.via-vcs]
          (fill-in "Verification Repository URL"
                   "https://github.com/example/clojars-dantheman")
          (press "Verify Groups"))
        (follow-redirect)
        (within [:div.info]
          (has (some-text? "The groups 'com.github.example' & 'net.github.example' have been verified"))))
    (assert-admin-email :success)))

(deftest user-cannot-verify-vcs-groups-with-missing-repo
  (with-redefs [http/head (constantly {:status 404})]
    (-> (session)
        (visit "/verify/group")
        (within [:div.via-vcs]
          (fill-in "Verification Repository URL"
                   "https://github.com/example/clojars-dantheman")
          (press "Verify Groups"))
        (follow-redirect)
        (within [:div.error]
          (has (some-text? "The verification repo does not exist"))))
    (assert-admin-email :failure)))
