(ns clojars.unit.models.group-setting-test
  (:require
   [clojars.models.group-setting :as group-setting-m]
   [clojars.test-helper :as help]
   [clojure.test :refer [deftest is use-fixtures]]
   [matcher-combinators.test]))

(use-fixtures :each
  help/with-clean-database)

(deftest group-settings
  ;; Test insert
  (group-setting-m/set-group-mfa-required help/*db* "my-group" true)
  (is (true? (:require_mfa_to_deploy (group-setting-m/get-group-settings help/*db* "my-group"))))
  ;; Test upsert
  (group-setting-m/set-group-mfa-required help/*db* "my-group" false)
  (is (false? (:require_mfa_to_deploy (group-setting-m/get-group-settings help/*db* "my-group")))))
