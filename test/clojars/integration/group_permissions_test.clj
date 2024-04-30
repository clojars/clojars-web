(ns clojars.integration.group-permissions-test
  (:require
   [clojars.db :as db]
   [clojars.email :as email]
   [clojars.integration.steps :refer [login-as register-as]]
   ;; for defmethods
   [clojars.notifications.group]
   [clojars.test-helper :as help]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [kerodon.core :refer [check choose fill-in press session visit within]]
   [kerodon.test :refer [has text?]]
   [net.cgrand.enlive-html :as enlive]
   [peridot.core :as p]))

(use-fixtures :each
  help/default-fixture
  help/with-clean-database
  help/run-test-app)

(deftest admin-can-add-member-to-group
  (-> (session (help/app))
      (register-as "fixture" "fixture@example.org" "password"))
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password")
      ((fn [session] (email/expect-mock-emails 2) session))
      (visit "/groups/org.clojars.dantheman")
      (fill-in [:#username] "fixture")
      (press "Add Permission")
      (within [:table.group-member-list
               [:tr enlive/last-of-type]
               [:td enlive/first-of-type]]
        (has (text? "fixture")))
      (within [:table.group-member-list
               [:tr enlive/last-of-type]
               [:td (enlive/nth-of-type 2)]]
        (has (text? "*")))
      (within [:table.group-member-list
               [:tr enlive/last-of-type]
               [:td (enlive/nth-of-type 3)]]
        (has (text? "No"))))

  (is (some #{"fixture"} (db/group-membernames help/*db* "org.clojars.dantheman" db/SCOPE-ALL)))

  (help/match-audit {:username "dantheman"}
                    {:tag "permission-added"
                     :user "dantheman"
                     :group_name "org.clojars.dantheman"
                     :message "user 'fixture' added as member with '*' scope"})

  (is (true? (email/wait-for-mock-emails)))
  (is (= 2 (count @email/mock-emails)))
  (is (= #{"fixture@example.org" "test@example.org"}
         (into #{} (map first) @email/mock-emails)))
  (is (every? #(= "A permission was added to the group org.clojars.dantheman"
                  %)
              (into [] (map second) @email/mock-emails)))
  (is (every? #(str/starts-with? % "User 'fixture' was added to the org.clojars.dantheman group with scope '*' by dantheman.\n\n")
              (into [] (map #(nth % 2)) @email/mock-emails))))

(deftest admin-can-toggle-member-to-admin
  (-> (session (help/app))
      (register-as "fixture" "fixture@example.org" "password"))
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password")
      (visit "/groups/org.clojars.dantheman")
      (fill-in [:#username] "fixture")
      (press "Add Permission")
      (within [:table.group-member-list
               [:tr enlive/last-of-type]
               [:td enlive/first-of-type]]
        (has (text? "fixture")))
      (within [:table.group-member-list
               [:tr enlive/last-of-type]
               [:td (enlive/nth-of-type 2)]]
        (has (text? "*")))
      (within [:table.group-member-list
               [:tr enlive/last-of-type]
               [:td (enlive/nth-of-type 3)]]
        (has (text? "No"))))

  (help/match-audit {:username "dantheman"}
                    {:tag "permission-added"
                     :user "dantheman"
                     :group_name "org.clojars.dantheman"
                     :message "user 'fixture' added as member with '*' scope"})

  (email/expect-mock-emails 2)
  (-> (session (help/app))
      (login-as "dantheman" "password")
      (visit "/groups/org.clojars.dantheman")
      (press "Toggle Admin")
      (within [:table.group-member-list
               [:tr enlive/last-of-type]
               [:td enlive/first-of-type]]
        (has (text? "fixture")))
      (within [:table.group-member-list
               [:tr enlive/last-of-type]
               [:td (enlive/nth-of-type 2)]]
        (has (text? "*")))
      (within [:table.group-member-list
               [:tr enlive/last-of-type]
               [:td (enlive/nth-of-type 3)]]
        (has (text? "Yes"))))

  (help/match-audit {:username "dantheman"}
                    {:tag "permission-added"
                     :user "dantheman"
                     :group_name "org.clojars.dantheman"
                     :message "user 'fixture' added as admin with '*' scope"})

  (is (true? (email/wait-for-mock-emails)))
  (is (= 2 (count @email/mock-emails)))
  (is (= #{"fixture@example.org" "test@example.org"}
         (into #{} (map first) @email/mock-emails)))
  (is (every? #(= "An admin permission was added to the group org.clojars.dantheman"
                  %)
              (into [] (map second) @email/mock-emails)))
  (is (every? #(str/starts-with? % "User 'fixture' was added as an admin to the org.clojars.dantheman group with scope '*' by dantheman.\n\n")
              (into [] (map #(nth % 2)) @email/mock-emails))))

(deftest admin-can-add-admin-to-group
  (-> (session (help/app))
      (register-as "fixture" "fixture@example.org" "password"))
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password")
      ((fn [session] (email/expect-mock-emails 2) session))
      (visit "/groups/org.clojars.dantheman")
      (fill-in [:#username] "fixture")
      (check [:#admin])
      (press "Add Permission")
      (within [:table.group-member-list
               [:tr enlive/last-of-type]
               [:td enlive/first-of-type]]
        (has (text? "fixture")))
      (within [:table.group-member-list
               [:tr enlive/last-of-type]
               [:td (enlive/nth-of-type 2)]]
        (has (text? "*")))
      (within [:table.group-member-list
               [:tr enlive/last-of-type]
               [:td (enlive/nth-of-type 3)]]
        (has (text? "Yes"))))

  (is (some #{"fixture"} (db/group-adminnames help/*db* "org.clojars.dantheman" db/SCOPE-ALL)))

  (help/match-audit {:username "dantheman"}
                    {:tag "permission-added"
                     :user "dantheman"
                     :group_name "org.clojars.dantheman"
                     :message "user 'fixture' added as admin with '*' scope"})

  (is (true? (email/wait-for-mock-emails)))
  (is (= 2 (count @email/mock-emails)))
  (is (= #{"fixture@example.org" "test@example.org"}
         (into #{} (map first) @email/mock-emails)))
  (is (every? #(= "An admin permission was added to the group org.clojars.dantheman"
                  %)
              (into [] (map second) @email/mock-emails)))
  (is (every? #(str/starts-with? % "User 'fixture' was added as an admin to the org.clojars.dantheman group with scope '*' by dantheman.\n\n")
              (into [] (map #(nth % 2)) @email/mock-emails))))

(deftest admin-can-remove-user-from-group
  (-> (session (help/app))
      (register-as "fixture" "fixture@example.org" "password"))
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password")
      (visit "/groups/org.clojars.dantheman")
      (fill-in [:#username] "fixture")
      ((fn [session] (email/expect-mock-emails 2) session))
      (press "Add Permission")
      ((fn [session]
         ;; clear the add emails
         (email/wait-for-mock-emails 1000)
         ;; Then prep for the remove emails
         (email/expect-mock-emails 2)
         session))
      (press "Remove Permission"))
  (help/match-audit {:username "dantheman"}
                    {:tag "permission-removed"
                     :user "dantheman"
                     :group_name "org.clojars.dantheman"
                     :message "user 'fixture' with scope '*' removed"})

  (is (true? (email/wait-for-mock-emails)))
  (is (= 2 (count @email/mock-emails)))
  (is (= #{"fixture@example.org" "test@example.org"}
         (into #{} (map first) @email/mock-emails)))
  (is (every? #(= "A permission was removed from the group org.clojars.dantheman"
                  %)
              (into [] (map second) @email/mock-emails)))
  (is (every? #(str/starts-with? % "User 'fixture' was removed from the org.clojars.dantheman group with scope '*' by dantheman.\n\n")
              (into [] (map #(nth % 2)) @email/mock-emails))))

(deftest user-must-exist-to-be-added-to-group
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password")
      (visit "/groups/org.clojars.dantheman")
      (fill-in [:#username] "fixture")
      (press "Add Permission")
      (within [:div.error :ul :li]
        (has (text? "No such user: fixture")))))

(deftest user-can-be-scoped-to-jars
  (-> (session (help/app))
      (register-as "fixture" "fixture@example.org" "password"))
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password"))
  ;; Add jars so they show in select
  (db/add-jar help/*db* "dantheman"
              {:group "org.clojars.dantheman"
               :name "test"
               :version "0.0.1"})
  (db/add-jar help/*db* "dantheman"
              {:group "org.clojars.dantheman"
               :name "test2"
               :version "0.0.1"})
  (-> (session (help/app))
      (login-as "dantheman" "password")
      (visit "/groups/org.clojars.dantheman")
      (fill-in [:#username] "fixture")
      (check [:#admin])
      (choose [:#scope_to_jar_select] "test")
      (press "Add Permission")
      (within [:table.group-member-list
               [:tr enlive/last-of-type]
               [:td enlive/first-of-type]]
        (has (text? "fixture")))
      (within [:table.group-member-list
               [:tr enlive/last-of-type]
               [:td (enlive/nth-of-type 2)]]
        (has (text? "test")))
      (within [:table.group-member-list
               [:tr enlive/last-of-type]
               [:td (enlive/nth-of-type 3)]]
        (has (text? "Yes")))
      (visit "/groups/org.clojars.dantheman")
      (fill-in [:#username] "fixture")
      (check [:#admin])
      (choose [:#scope_to_jar_select] "test2")
      (press "Add Permission")
      (within [:table.group-member-list
               [:tr enlive/last-of-type]
               [:td enlive/first-of-type]]
        (has (text? "fixture")))
      (within [:table.group-member-list
               [:tr enlive/last-of-type]
               [:td (enlive/nth-of-type 2)]]
        (has (text? "test2")))
      (within [:table.group-member-list
               [:tr enlive/last-of-type]
               [:td (enlive/nth-of-type 3)]]
        (has (text? "Yes")))))

(deftest user-can-be-toggled-with-scoping
  (-> (session (help/app))
      (register-as "fixture" "fixture@example.org" "password"))
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password"))
  ;; Add jars so they show in select
  (db/add-jar help/*db* "dantheman"
              {:group "org.clojars.dantheman"
               :name "test"
               :version "0.0.1"})
  (-> (session (help/app))
      (login-as "dantheman" "password")
      (visit "/groups/org.clojars.dantheman")
      (fill-in [:#username] "fixture")
      (check [:#admin])
      (choose [:#scope_to_jar_select] "test")
      (press "Add Permission")
      (within [:table.group-member-list
               [:tr enlive/last-of-type]
               [:td enlive/first-of-type]]
        (has (text? "fixture")))
      (within [:table.group-member-list
               [:tr enlive/last-of-type]
               [:td (enlive/nth-of-type 2)]]
        (has (text? "test")))
      (within [:table.group-member-list
               [:tr enlive/last-of-type]
               [:td (enlive/nth-of-type 3)]]
        (has (text? "Yes")))
      (visit "/groups/org.clojars.dantheman")
      (fill-in [:#username] "fixture")
      (choose [:#scope_to_jar_select] "test")
      (press "Add Permission")
      (within [:table.group-member-list
               [:tr enlive/last-of-type]
               [:td enlive/first-of-type]]
        (has (text? "fixture")))
      (within [:table.group-member-list
               [:tr enlive/last-of-type]
               [:td (enlive/nth-of-type 2)]]
        (has (text? "test")))
      (within [:table.group-member-list
               [:tr enlive/last-of-type]
               [:td (enlive/nth-of-type 3)]]
        (has (text? "No")))))

(deftest user-can-be-scoped-to-new-jar
  (-> (session (help/app))
      (register-as "fixture" "fixture@example.org" "password"))
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password"))
  (-> (session (help/app))
      (login-as "dantheman" "password")
      (visit "/groups/org.clojars.dantheman")
      (fill-in [:#username] "fixture")
      (check [:#admin])
      (choose [:#scope_to_jar_select] ":new")
      (fill-in [:#scope_to_jar_new] "test")
      (press "Add Permission")
      (within [:table.group-member-list
               [:tr enlive/last-of-type]
               [:td enlive/first-of-type]]
        (has (text? "fixture")))
      (within [:table.group-member-list
               [:tr enlive/last-of-type]
               [:td (enlive/nth-of-type 2)]]
        (has (text? "test")))
      (within [:table.group-member-list
               [:tr enlive/last-of-type]
               [:td (enlive/nth-of-type 3)]]
        (has (text? "Yes")))))

(deftest user-cannot-be-scoped-to-jar-when-is-all-scoped
  (-> (session (help/app))
      (register-as "fixture" "fixture@example.org" "password"))
  (-> (session (help/app))
      (register-as "fixture2" "fixture2@example.org" "password"))
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password"))
  ;; Add jars so they show in select
  (db/add-jar help/*db* "dantheman"
              {:group "org.clojars.dantheman"
               :name "test"
               :version "0.0.1"})
  (testing "As admin for all scope"
    (-> (session (help/app))
        (login-as "dantheman" "password")
        (visit "/groups/org.clojars.dantheman")
        (fill-in [:#username] "fixture")
        (check [:#admin])
        (press "Add Permission")
        (within [:table.group-member-list
                 [:tr enlive/last-of-type]
                 [:td enlive/first-of-type]]
          (has (text? "fixture")))
        (within [:table.group-member-list
                 [:tr enlive/last-of-type]
                 [:td (enlive/nth-of-type 2)]]
          (has (text? "*")))
        (within [:table.group-member-list
                 [:tr enlive/last-of-type]
                 [:td (enlive/nth-of-type 3)]]
          (has (text? "Yes")))

        ;; As admin for project scope
        (visit "/groups/org.clojars.dantheman")
        (fill-in [:#username] "fixture")
        (choose [:#scope_to_jar_select] "test")
        (check [:#admin])
        (press "Add Permission")
        (within [:div.error :ul :li]
          (has (text? "User has '*' scope, so can't be further scoped")))

        ;; As member for project scope
        (visit "/groups/org.clojars.dantheman")
        (fill-in [:#username] "fixture")
        (choose [:#scope_to_jar_select] "test")
        (press "Add Permission")
        (within [:div.error :ul :li]
          (has (text? "User has '*' scope, so can't be further scoped")))))

  (testing "As member for all scope"
    (-> (session (help/app))
        (login-as "dantheman" "password")
        (visit "/groups/org.clojars.dantheman")
        (fill-in [:#username] "fixture2")
        (press "Add Permission")
        (within [:table.group-member-list
                 [:tr enlive/last-of-type]
                 [:td enlive/first-of-type]]
          (has (text? "fixture2")))
        (within [:table.group-member-list
                 [:tr enlive/last-of-type]
                 [:td (enlive/nth-of-type 2)]]
          (has (text? "*")))
        (within [:table.group-member-list
                 [:tr enlive/last-of-type]
                 [:td (enlive/nth-of-type 3)]]
          (has (text? "No")))

        ;; As admin for project scope
        (visit "/groups/org.clojars.dantheman")
        (fill-in [:#username] "fixture2")
        (choose [:#scope_to_jar_select] "test")
        (check [:#admin])
        (press "Add Permission")
        (within [:div.error :ul :li]
          (has (text? "User has '*' scope, so can't be further scoped")))

        ;; As member for project scope
        (visit "/groups/org.clojars.dantheman")
        (fill-in [:#username] "fixture2")
        (choose [:#scope_to_jar_select] "test")
        (press "Add Permission")
        (within [:div.error :ul :li]
          (has (text? "User has '*' scope, so can't be further scoped"))))))

(deftest user-cannot-be-scoped-to-all-when-caller-is-scoped-to-jar
  (-> (session (help/app))
      (register-as "fixture" "fixture@example.org" "password"))
  (-> (session (help/app))
      (register-as "fixture2" "fixture@example.org" "password"))
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password"))
  ;; Add jars so they show in select
  (db/add-jar help/*db* "dantheman"
              {:group "org.clojars.dantheman"
               :name "test"
               :version "0.0.1"})
  (-> (session (help/app))
      (login-as "dantheman" "password")
      (visit "/groups/org.clojars.dantheman")
      (fill-in [:#username] "fixture")
      (choose [:#scope_to_jar_select] "test")
      (check [:#admin])
      (press "Add Permission"))
  (-> (session (help/app))
      (login-as "fixture" "password")
      ;; The UI hides invalid options from us, so we have to make the request
      ;; manually
      (p/request "/groups" :request-method :post
                 :params {:username "fixture2"
                          :admin "1"})
      (help/assert-status 403)))

(deftest user-cannot-be-scoped-to-jar-that-caller-does-not-have-access-to
  (-> (session (help/app))
      (register-as "fixture" "fixture@example.org" "password"))
  (-> (session (help/app))
      (register-as "fixture2" "fixture@example.org" "password"))
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password"))
  ;; Add jars so they show in select
  (db/add-jar help/*db* "dantheman"
              {:group "org.clojars.dantheman"
               :name "test"
               :version "0.0.1"})
  (db/add-jar help/*db* "dantheman"
              {:group "org.clojars.dantheman"
               :name "test2"
               :version "0.0.1"})
  (-> (session (help/app))
      (login-as "dantheman" "password")
      (visit "/groups/org.clojars.dantheman")
      (fill-in [:#username] "fixture")
      (choose [:#scope_to_jar_select] "test")
      (check [:#admin])
      (press "Add Permission"))
  (-> (session (help/app))
      (login-as "fixture" "password")
      ;; The UI hides invalid options from us, so we have to make the request
      ;; manually
      (p/request "/groups" :request-method :post
                 :params {:username "fixture2"
                          :scope_to_jar_select "test2"
                          :admin "1"})
      (help/assert-status 403)))

(deftest user-cannot-be-scoped-to-all-when-is-jar-scoped
  (-> (session (help/app))
      (register-as "fixture" "fixture@example.org" "password"))
  (-> (session (help/app))
      (register-as "fixture2" "fixture2@example.org" "password"))
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password"))
  ;; Add jars so they show in select
  (db/add-jar help/*db* "dantheman"
              {:group "org.clojars.dantheman"
               :name "test"
               :version "0.0.1"})
  (testing "As admin for jar scope"
    (-> (session (help/app))
        (login-as "dantheman" "password")
        (visit "/groups/org.clojars.dantheman")
        (fill-in [:#username] "fixture")
        (choose [:#scope_to_jar_select] "test")
        (check [:#admin])
        (press "Add Permission")
        (within [:table.group-member-list
                 [:tr enlive/last-of-type]
                 [:td enlive/first-of-type]]
          (has (text? "fixture")))
        (within [:table.group-member-list
                 [:tr enlive/last-of-type]
                 [:td (enlive/nth-of-type 2)]]
          (has (text? "test")))
        (within [:table.group-member-list
                 [:tr enlive/last-of-type]
                 [:td (enlive/nth-of-type 3)]]
          (has (text? "Yes")))

        ;; As admin for all scope
        (visit "/groups/org.clojars.dantheman")
        (fill-in [:#username] "fixture")
        (check [:#admin])
        (press "Add Permission")
        (within [:div.error :ul :li]
          (has (text? "User has project scope, so can't be given '*' scope")))

        ;; As member for project scope
        (visit "/groups/org.clojars.dantheman")
        (fill-in [:#username] "fixture")
        (press "Add Permission")
        (within [:div.error :ul :li]
          (has (text? "User has project scope, so can't be given '*' scope")))))

  (testing "As member for jar scope"
    (-> (session (help/app))
        (login-as "dantheman" "password")
        (visit "/groups/org.clojars.dantheman")
        (fill-in [:#username] "fixture2")
        (choose [:#scope_to_jar_select] "test")
        (press "Add Permission")
        (within [:table.group-member-list
                 [:tr enlive/last-of-type]
                 [:td enlive/first-of-type]]
          (has (text? "fixture2")))
        (within [:table.group-member-list
                 [:tr enlive/last-of-type]
                 [:td (enlive/nth-of-type 2)]]
          (has (text? "test")))
        (within [:table.group-member-list
                 [:tr enlive/last-of-type]
                 [:td (enlive/nth-of-type 3)]]
          (has (text? "No")))

        ;; As admin for all scope
        (visit "/groups/org.clojars.dantheman")
        (fill-in [:#username] "fixture2")
        (check [:#admin])
        (press "Add Permission")
        (within [:div.error :ul :li]
          (has (text? "User has project scope, so can't be given '*' scope")))

        ;; As member for all scope
        (visit "/groups/org.clojars.dantheman")
        (fill-in [:#username] "fixture2")
        (press "Add Permission")
        (within [:div.error :ul :li]
          (has (text? "User has project scope, so can't be given '*' scope"))))))


