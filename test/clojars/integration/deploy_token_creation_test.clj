(ns clojars.integration.deploy-token-creation-test
  (:require
   [clojars.email :as email]
   [clojars.integration.steps :refer [create-deploy-token register-as]]
   ;; for defmethod side effects when handler runs
   [clojars.notifications.token]
   [clojars.test-helper :as help]
   [clojure.test :refer [deftest is use-fixtures]]
   [kerodon.core :refer [session]]))

(use-fixtures :each
  help/default-fixture
  help/with-clean-database
  help/run-test-app)

(deftest creating-deploy-token-sends-email-to-user
  (-> (session (help/app))
      (register-as "fixture" "fixture@example.org" "password1234"))
  (-> (session (help/app))
      (create-deploy-token "fixture" "password1234" "my-laptop"))
  (is (true? (email/wait-for-mock-emails)))
  (let [[to subject body] (first @email/mock-emails)]
    (is (= "fixture@example.org" to))
    (is (= "A deploy token was created on your Clojars account" subject))
    (is (re-find #"Hello," body))
    (is (re-find #"my-laptop" body))
    (is (re-find #"Scope: \* \(any group/artifact you have access to\)" body))
    (is (re-find #"Single use: no" body))
    (is (re-find #"Expires: never" body))
    (is (re-find #"https://clojars.org/tokens" body))
    (is (not (re-find #"CLOJARS_" body))))
  (help/assert-email-user-footer [["fixture@example.org" "fixture"]]
                                 @email/mock-emails))
