(ns clojars.test.unit.db
  (:require [clj-time.core :as time]
            [clojars.web.browse :as browse]
            [clojars.test.test-helper :as help]
            [clojure.test :refer :all]
            [clojars.db :as db]))

(use-fixtures :each
  help/using-test-config
  help/with-clean-database)

(defn submap [s m]
  (every? (fn [[k v]] (= (m k) v)) s))

(deftest added-users-can-be-found
  (let [email "test@example.com"
        name "testuser"
        password "password"
        pgp-key "aoeu"]
      (is (db/add-user help/*db* email name password pgp-key (time/epoch)))
      (are [x] (submap {:email email
                        :user name}
                       x)
           (db/find-user help/*db* name)
           (db/find-user-by-user-or-email help/*db* name)
           (db/find-user-by-user-or-email help/*db* email))))

(deftest user-does-not-exist
  (is (not (db/find-user-by-user-or-email help/*db* "test2@example.com"))))

(deftest added-users-can-be-found-by-password-reset-code-except-when-expired
  (let [email "test@example.com"
        name "testuser"
        password "password"
        pgp-key "aoeu"]
      (db/add-user help/*db* email name password pgp-key (time/epoch))
      (let [reset-code "1"]
        (db/set-password-reset-code! help/*db* "test@example.com" reset-code (time/epoch))
        (is (submap {:email email
                     :user name
                     :password_reset_code reset-code}
                    (db/find-user-by-password-reset-code help/*db* reset-code (time/epoch))))
        (is (not (db/find-user-by-password-reset-code
                  help/*db* reset-code
                  (time/plus (time/epoch) (time/days 1) (time/seconds 1))))))))

(deftest updated-users-can-be-found
  (let [email "test@example.com"
        name "testuser"
        password "password"
        pgp-key "aoeu"
        email2 "test2@example.com"
        name2 "testuser2"
        password2 "password2"
        pgp-key2 "aoeu2"]
    (is (db/add-user help/*db* email name password pgp-key (time/epoch)))
    (is (db/update-user help/*db* name email2 name2 password2 pgp-key2))
    (are [x] (submap {:email email2
                      :user name2
                      :pgp_key pgp-key2
                      :created 0}
                     x)
         (db/find-user help/*db* name2)
         (db/find-user-by-user-or-email help/*db* name2)
         (db/find-user-by-user-or-email help/*db* email2))
    (is (not (db/find-user help/*db* name)))))

(deftest added-users-are-added-only-to-their-org-clojars-group
  (let [email "test@example.com"
        name "testuser"
        password "password"
        pgp-key "aoeu"]
    (is (db/add-user help/*db* email name password pgp-key (time/epoch)))
    (is (= ["testuser"]
           (db/group-membernames help/*db* (str "org.clojars." name))))
    (is (= ["org.clojars.testuser"]
           (db/find-groupnames help/*db* name)))))

(deftest users-can-be-added-to-groups
  (let [email "test@example.com"
        name "testuser"
        password "password"
        pgp-key "aoeu"]
    (db/add-user help/*db* email name password pgp-key (time/epoch))
    (db/add-member help/*db* "test-group" name "some-dude")
    (is (= ["testuser"] (db/group-membernames help/*db* "test-group")))
    (is (some #{"test-group"} (db/find-groupnames help/*db* name)))))

;;TODO: Tests below should have the users added first.
;;Currently user unenforced foreign keys are by name
;;so these are faking relationships

(deftest added-jars-can-be-found
  (let [name "tester"
        jarmap {:name name :group name :version "1.0"
                :description "An dog awesome and non-existent test jar."
                :homepage "http://clojars.org/"
                :authors "Alex Osborne, a little fish"}
        result {:jar_name name
                :version "1.0"
                :homepage "http://clojars.org/"
                :scm nil
                :user "test-user"
                :created 0
                :group_name name
                :authors "Alex Osborne, a little fish"
                :description "An dog awesome and non-existent test jar."}]
    (is (db/add-jar help/*db* "test-user" jarmap (time/epoch)))
    (are [x] (submap result x)
         (db/find-jar help/*db* name name)
         (first (db/jars-by-groupname help/*db* name))
         (first (db/jars-by-username help/*db* "test-user")))))

(deftest jars-can-be-deleted-by-group
  (let [group "foo"
        jar {:name "one" :group group :version "1.0"
             :description "An dog awesome and non-existent test jar."
             :homepage "http://clojars.org/"
             :authors "Alex Osborne, a little fish"}]
    (db/add-jar help/*db* "test-user" jar (time/epoch))
    (db/add-jar help/*db* "test-user"
                (assoc jar :name "two")
                (time/epoch))
    (db/add-jar help/*db* "test-user"
                (assoc jar :group "another")
                (time/plus (time/epoch) (time/seconds 1)))
    (is (= 2 (count (db/jars-by-groupname help/*db* group))))
    (db/delete-jars help/*db* group)
    (is (empty? (db/jars-by-groupname help/*db* group)))
    (is (= 1 (count (db/jars-by-groupname help/*db* "another"))))))

(deftest jars-can-be-deleted-by-group-and-jar-id
  (let [group "foo"
        jar {:name "one" :group group :version "1.0"
             :description "An dog awesome and non-existent test jar."
             :homepage "http://clojars.org/"
             :authors "Alex Osborne, a little fish"}]
    (db/add-jar help/*db* "test-user" jar (time/epoch))
    (db/add-jar help/*db* "test-user"
                (assoc jar :name "two")
                (time/epoch))
    (is (= 2 (count (db/jars-by-groupname help/*db* group))))
    (db/delete-jars help/*db* group "one")
    (is (= 1 (count (db/jars-by-groupname help/*db* group))))))

(deftest jars-can-be-deleted-by-group-and-jar-id-and-version
  (let [group "foo"
        jar {:name "one" :group group :version "1.0"
             :description "An dog awesome and non-existent test jar."
             :homepage "http://clojars.org/"
             :authors "Alex Osborne, a little fish"}]

    (db/add-jar help/*db* "test-user" jar (time/epoch))
    (db/jars-by-groupname help/*db* group)
    (db/add-jar help/*db* "test-user"
                (assoc jar
                       :version "2.0")
                (time/plus (time/epoch) (time/seconds 1)))
    (db/jars-by-groupname help/*db* group)
    (is (= "2.0" (-> (db/jars-by-groupname help/*db* group) first :version)))
    (db/delete-jars help/*db* group "one" "2.0")
    (is (= "1.0" (-> (db/jars-by-groupname help/*db* group) first :version)))))

(deftest jars-by-group-only-returns-most-recent-version
  (let [name "tester"
        jarmap {:name name :group name :version "1" }
        result {:jar_name name
                :version "2"
                :user "test-user"
                :group_name name }]
    (is (db/add-jar help/*db* "test-user" jarmap (time/epoch)))
    (is (db/add-jar help/*db* "test-user" (assoc jarmap :version "2")
                    (time/plus (time/epoch) (time/seconds 1))))
    (let [jars (db/jars-by-groupname help/*db* name)]
      (dorun (map #(is (= %1 (select-keys %2 (keys %1)))) [result] jars))
      (is (= 1 (count jars))))))

(deftest jars-with-multiple-versions
  (let [name "tester"
        jarmap {:name name :group name :version "1" }]
    (is (db/add-jar help/*db* "test-user" jarmap (time/epoch)))
    (is (db/add-jar help/*db* "test-user" (assoc jarmap :version "2")
                    (time/plus (time/epoch) (time/seconds 1))))
    (is (db/add-jar help/*db* "test-user" (assoc jarmap :version "3")
                    (time/plus (time/epoch) (time/seconds 2))))
    (is (db/add-jar help/*db* "test-user" (assoc jarmap :version "4-SNAPSHOT")
                    (time/plus (time/epoch) (time/seconds 3))))
    (is (= 4 (db/count-versions help/*db* name name)))
    (is (= ["4-SNAPSHOT" "3" "2" "1"]
           (map :version (db/recent-versions help/*db* name name))))
    (is (= ["4-SNAPSHOT"] (map :version (db/recent-versions help/*db* name name 1))))
    (is (= "3" (:version (db/find-jar help/*db* name name))))
    (is (= "4-SNAPSHOT" (:version (db/find-jar help/*db* name name "4-SNAPSHOT"))))))

(deftest jars-by-group-returns-all-jars-in-group
  (let [name "tester"
        jarmap {:name name :group name :version "1" }
        result {:jar_name name
                :version "1"
                :group_name name }]
    (db/add-member help/*db* name "test-user" "some-dude")
    (db/add-member help/*db* "tester-group" "test-user2" "some-dude")
    (db/add-member help/*db* name "test-user2" "some-dude")
    (is (db/add-jar help/*db* "test-user" jarmap (time/epoch)))
    (is (db/add-jar help/*db* "test-user" (assoc jarmap :name "tester2")
                    (time/plus (time/epoch) (time/seconds 1))))
    (is (db/add-jar help/*db* "test-user2" (assoc jarmap :name "tester3")
                    (time/plus (time/epoch) (time/seconds 2))))
    (is (db/add-jar help/*db* "test-user2" (assoc jarmap :group "tester-group")
                    (time/plus (time/epoch) (time/seconds 3))))
    (let [jars (db/jars-by-groupname help/*db* name)]
      (dorun (map #(is (submap %1 %2))
                  [result
                   (assoc result :jar_name "tester2")
                   (assoc result :jar_name "tester3")]
                  jars))
      (is (= 3 (count jars))))))

(deftest jars-by-user-only-returns-most-recent-version
  (let [name "tester"
        jarmap {:name name :group name :version "1" }
        result {:jar_name name
                :version "2"
                :user "test-user"
                :group_name name }]
    (is (db/add-jar help/*db* "test-user" jarmap (time/epoch)))
    (is (db/add-jar help/*db* "test-user" (assoc jarmap :version "2")
                    (time/plus (time/epoch) (time/seconds 1))))
    (let [jars (db/jars-by-username help/*db* "test-user")]
      (dorun (map #(is (= %1 (select-keys %2 (keys %1)))) [result] jars))
      (is (= 1 (count jars))))))

(deftest jars-by-user-returns-all-jars-by-user
  (let [name "tester"
        jarmap {:name name :group name :version "1" }
        result {:jar_name name
                :user "test-user"
                :version "1"
                :group_name name }]
    (db/add-member help/*db* name "test-user" "some-dude")
    (db/add-member help/*db* "tester-group" "test-user" "some-dude")
    (db/add-member help/*db* name "test-user2" "some-dude")
    (is (db/add-jar help/*db* "test-user" jarmap (time/epoch)))
    (is (db/add-jar help/*db* "test-user" (assoc jarmap :name "tester2")
                    (time/plus (time/epoch) (time/seconds 1))))
    (is (db/add-jar help/*db* "test-user2" (assoc jarmap :name "tester3")
                    (time/plus (time/epoch) (time/seconds 2))))
    (is (db/add-jar help/*db* "test-user" (assoc jarmap :group "tester-group")
                    (time/plus (time/epoch) (time/seconds 3))))
    (let [jars (db/jars-by-username help/*db* "test-user")]
      (dorun (map #(is (submap %1 %2))
                  [result
                   (assoc result :jar_name "tester2")
                   (assoc result :group_name "tester-group")]
                  jars))
      (is (= 3 (count jars))))))

(deftest recent-jars-returns-6-most-recent-jars-only-most-recent-version
  (let [name "tester"
        jarmap {:name name :group name
                :description "An dog awesome and non-existent test jar."
                :homepage "http://clojars.org/"
                :authors "Alex Osborne, a little fish"
                :version "1"}
        result {:user "test-user"
                :jar_name name
                :version "1"
                :homepage "http://clojars.org/"
                :scm nil
                :group_name name
                :authors "Alex Osborne, a little fish"
                :description "An dog awesome and non-existent test jar."}]
    (db/add-jar help/*db* "test-user" (assoc jarmap :name "1")
                (time/plus (time/epoch) (time/seconds 1)))
    (db/add-jar help/*db* "test-user" (assoc jarmap :name "2")
                (time/plus (time/epoch) (time/seconds 2)))
    (db/add-jar help/*db* "test-user" (assoc jarmap :name "3")
                (time/plus (time/epoch) (time/seconds 3)))
    (db/add-jar help/*db* "test-user" (assoc jarmap :name "4")
                (time/plus (time/epoch) (time/seconds 4)))
    (db/add-jar help/*db* "test-user" (assoc jarmap :version "2")
                (time/plus (time/epoch) (time/seconds 5)))
    (db/add-jar help/*db* "test-user" (assoc jarmap :name "6")
                (time/plus (time/epoch) (time/seconds 6)))
    (db/add-jar help/*db* "test-user" (assoc jarmap :version "3")
                (time/plus (time/epoch) (time/seconds 7)))
    (dorun (map #(is (submap %1 %2))
                [(assoc result :version "3")
                 (assoc result :jar_name "6")
                 (assoc result :jar_name "4")
                 (assoc result :jar_name "3")
                 (assoc result :jar_name "2")]
                (db/recent-jars help/*db*)))))

(deftest count-projects-works
  (db/add-jar help/*db* "test-user" {:name "rock" :group "jester" :version "0.1"}
              (time/epoch))
  (db/add-jar help/*db* "test-user" {:name "rock" :group "tester" :version "0.1"}
              (time/epoch))
  (db/add-jar help/*db* "test-user" {:name "rock" :group "tester" :version "0.2"}
              (time/plus (time/epoch) (time/seconds 1)))
  (db/add-jar help/*db* "test-user" {:name "paper" :group "tester" :version "0.1"}
              (time/epoch))
  (db/add-jar help/*db* "test-user" {:name "scissors" :group "tester" :version "0.1"}
              (time/epoch))
  (is (= (db/count-all-projects help/*db*) 4))
  (is (= (db/count-projects-before help/*db* "a") 0))
  (is (= (db/count-projects-before help/*db* "tester/rock") 2))
  (is (= (db/count-projects-before help/*db* "tester/rocks") 3))
  (is (= (db/count-projects-before help/*db* "z") 4)))

;; TODO: recent-versions
