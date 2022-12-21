(ns clojars.integration.group-verification-test
  (:require
   [clojars.integration.steps :refer [register-as]]
   [clojars.test-helper :as help]
   [clojure.test :refer [deftest use-fixtures]]
   [kerodon.core :refer [fill-in follow-redirect
                         press session visit within]]
   [kerodon.test :refer [has some-text?]]))

(use-fixtures :each
  help/default-fixture
  help/with-clean-database)

(deftest user-can-verify-group
  (help/with-TXT ["clojars dantheman"]
    (-> (session (help/app))
        (register-as "dantheman" "test@example.org" "password")
        (follow-redirect)
        (visit "/verify/group")
        (fill-in "Group name" "com.example")
        (fill-in "Domain with TXT record" "example.com")
        (press "Verify Group")
        (follow-redirect)
        (within [:div.info]
                (has (some-text? "The group 'com.example' has been verified"))))))

(deftest user-cannot-verify-non-corresponding-group
  (help/with-TXT ["clojars dantheman"]
    (-> (session (help/app))
        (register-as "dantheman" "test@example.org" "password")
        (follow-redirect)
        (visit "/verify/group")
        (fill-in "Group name" "com.example")
        (fill-in "Domain with TXT record" "example.org")
        (press "Verify Group")
        (follow-redirect)
        (within [:div.error]
                (has (some-text? "Group and domain do not correspond with each other"))))))
