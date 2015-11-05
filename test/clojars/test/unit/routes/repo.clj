(ns clojars.test.unit.routes.repo
    (:require [clojure.test :refer :all]
              [clojars.routes.repo :refer :all]
              [clojars.db :as db]
              [clojars.test.test-helper :as help]))

(deftest check-group-validates-group-name-format
  (is (thrown? Exception (check-group nil "test-user" nil)))
  (is (thrown? Exception (check-group nil "test-user" "")))
  (is (thrown? Exception (check-group nil "test-user" "HI")))
  (is (thrown? Exception (check-group nil "test-user" "lein*")))
  (is (thrown? Exception (check-group nil "test-user" "lein=")))
  (is (thrown? Exception (check-group nil "test-user" "lein>")))
  (is (thrown? Exception (check-group nil "test-user" "べ")))
  (help/with-clean-database
    (fn []
      (is (check-group help/*db* "test-user" "hi"))
      (is (check-group help/*db* "test-user" "hi-"))
      (is (check-group help/*db* "test-user" "hi_1...2")))))

(deftest check-group-validates-group-name-is-not-reserved
  (doseq [group reserved-names]
    (is (thrown? Exception (check-group nil "test-user" group)))))

(deftest check-group-validates-group-permissions
  (help/with-clean-database
    (fn []
      (db/add-member help/*db* "group-name" "some-user" "some-dude")
      (is (thrown? Exception (check-group help/*db* "test-user" "group-name"))))))

(deftest check-group-creates-single-member-group-for-user
  (help/with-clean-database
    (fn []
      (check-group help/*db* "test-user" "group-name")
      (is (= ["test-user"] (db/group-membernames help/*db* "group-name")))
      (is (= ["group-name"]
             (db/find-groupnames help/*db* "test-user"))))))
