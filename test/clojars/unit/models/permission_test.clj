(ns clojars.unit.models.permission-test
  (:require
   [clojars.models.permission :as permission-m]
   [clojars.models.user :as user-m]
   [clojars.test-helper :as help]
   [clojure.test :refer [deftest is use-fixtures]]
   [matcher-combinators.test]))

(use-fixtures :each
  help/with-clean-database)

(deftest members-can-be-added-to-groups
  (let [email "test@example.com"
        name "testuser"
        password "password1234"]
    (user-m/add-user help/*db* email name password)
    (permission-m/add-member help/*db* "test-group" permission-m/SCOPE-ALL name "some-dude")
    (is (= ["testuser"] (permission-m/group-activenames help/*db* "test-group")))
    (is (= ["testuser"] (permission-m/group-membernames help/*db* "test-group" permission-m/SCOPE-ALL)))
    (is (= [] (permission-m/group-adminnames help/*db* "test-group" permission-m/SCOPE-ALL)))
    (is (some #{"test-group"} (permission-m/find-groupnames help/*db* name)))

    (permission-m/add-member help/*db* "test-group2" "a-project" name "some-dude")
    (is (= ["testuser"] (permission-m/group-activenames help/*db* "test-group2")))
    (is (= ["testuser"] (permission-m/group-membernames help/*db* "test-group2" "a-project")))
    (is (= [] (permission-m/group-membernames help/*db* "test-group2" permission-m/SCOPE-ALL)))
    (is (= [] (permission-m/group-adminnames help/*db* "test-group2" "a-project")))
    (is (some #{"test-group2"} (permission-m/find-groupnames help/*db* name)))))

(deftest admins-can-be-added-to-groups
  (let [email "test@example.com"
        name "testadmin"
        password "password1234"]
    (user-m/add-user help/*db* email name password)
    (permission-m/add-admin help/*db* "test-group" permission-m/SCOPE-ALL name "some-dude")
    (is (= ["testadmin"] (permission-m/group-activenames help/*db* "test-group")))
    (is (= [] (permission-m/group-membernames help/*db* "test-group" permission-m/SCOPE-ALL)))
    (is (= ["testadmin"] (permission-m/group-adminnames help/*db* "test-group" permission-m/SCOPE-ALL)))
    (is (some #{"test-group"} (permission-m/find-groupnames help/*db* name)))

    (permission-m/add-admin help/*db* "test-group2" "a-project" name "some-dude")
    (is (= ["testadmin"] (permission-m/group-activenames help/*db* "test-group2")))
    (is (= [] (permission-m/group-membernames help/*db* "test-group2" "a-project")))
    (is (= ["testadmin"] (permission-m/group-adminnames help/*db* "test-group2" "a-project")))
    (is (= [] (permission-m/group-adminnames help/*db* permission-m/SCOPE-ALL "a-project")))
    (is (some #{"test-group2"} (permission-m/find-groupnames help/*db* name)))))
