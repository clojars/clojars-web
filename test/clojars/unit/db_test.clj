(ns clojars.unit.db-test
  (:require
   [buddy.core.codecs :as buddy.codecs]
   [buddy.core.hash :as buddy.hash]
   [clojars.db :as db]
   [clojars.test-helper :as help]
   [clojars.time :as time]
   [clojure.test :refer [are deftest is use-fixtures]]
   [matcher-combinators.test])
  (:import
   (clojure.lang
    ExceptionInfo)
   (java.sql
    Timestamp)))

(use-fixtures :each
  help/with-clean-database)

(deftest added-users-can-be-found
  (let [email "test@example.com"
        name "testuser"
        password "password"]
    (db/add-user help/*db* email name password)
    (are [x] (match? {:email email
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
        password "password"]
    (db/add-user help/*db* email name password)
    (let [reset-code (db/set-password-reset-code! help/*db* "testuser")]
      (is (match? {:email email
                   :user name
                   :password_reset_code reset-code}
                  (db/find-user-by-password-reset-code help/*db* reset-code)))
      (time/with-now (time/days-from 2)
        (is (not (db/find-user-by-password-reset-code help/*db* reset-code)))))))

(deftest renamed-users-can-be-found
  (let [email "test@example.com"
        name "testuser"
        password "password"
        created-at (Timestamp. 0)
        name2 "testuser2"]
    (help/with-time created-at
      (db/add-user help/*db* email name password)
      (help/with-time (Timestamp. 1)
        (db/rename-user help/*db* name name2)
        (are [x] (match? {:user name2
                          :created created-at}
                         x)
          (db/find-user help/*db* name2)
          (db/find-user-by-user-or-email help/*db* name2)))
      (is (not (db/find-user help/*db* name))))))

(deftest updated-users-can-be-found
  (let [email "test@example.com"
        name "testuser"
        password "password"
        created-at (Timestamp. 0)
        email2 "test2@example.com"
        password2 "password2"]
    (help/with-time created-at
      (db/add-user help/*db* email name password)
      (help/with-time (Timestamp. 1)
        (db/update-user help/*db* name email2 password2)
        (is (match?
             {:email email2
              :created created-at}
             (db/find-user-by-user-or-email help/*db* email2)))))))

(deftest update-user-works-when-password-is-blank
  (let [email "test@example.com"
        name "testuser"
        password "password"
        email2 "test2@example.com"
        password2 ""]
    (db/add-user help/*db* email name password)
    (let [old-user (db/find-user help/*db* name)]
      (db/update-user help/*db* name email2 password2)
      (let [user (db/find-user help/*db* name)]
        (is (= email2 (:email user)))
        (is (= (:password old-user) (:password user)))))))

(deftest added-users-are-added-only-to-their-net-and-org-clojars-group-as-admins-and-those-groups-are-verified
  (let [email "test@example.com"
        name "testuser"
        password "password"]
    (db/add-user help/*db* email name password)
    (is (= ["testuser"] (db/group-adminnames help/*db* (str "org.clojars." name) db/SCOPE-ALL)))
    (is (= ["testuser"] (db/group-activenames help/*db* (str "org.clojars." name))))
    (is (= [] (db/group-membernames help/*db* (str "org.clojars." name)  db/SCOPE-ALL)))
    (is (= ["testuser"] (db/group-adminnames help/*db* (str "net.clojars." name) db/SCOPE-ALL)))
    (is (= ["testuser"] (db/group-activenames help/*db* (str "net.clojars." name))))
    (is (= [] (db/group-membernames help/*db* (str "net.clojars." name) db/SCOPE-ALL)))
    (is (= ["net.clojars.testuser" "org.clojars.testuser"]
           (db/find-groupnames help/*db* name)))))

(deftest members-can-be-added-to-groups
  (let [email "test@example.com"
        name "testuser"
        password "password"]
    (db/add-user help/*db* email name password)
    (db/add-member help/*db* "test-group" db/SCOPE-ALL name "some-dude")
    (is (= ["testuser"] (db/group-activenames help/*db* "test-group")))
    (is (= ["testuser"] (db/group-membernames help/*db* "test-group" db/SCOPE-ALL)))
    (is (= [] (db/group-adminnames help/*db* "test-group" db/SCOPE-ALL)))
    (is (some #{"test-group"} (db/find-groupnames help/*db* name)))

    (db/add-member help/*db* "test-group2" "a-project" name "some-dude")
    (is (= ["testuser"] (db/group-activenames help/*db* "test-group2")))
    (is (= ["testuser"] (db/group-membernames help/*db* "test-group2" "a-project")))
    (is (= [] (db/group-membernames help/*db* "test-group2" db/SCOPE-ALL)))
    (is (= [] (db/group-adminnames help/*db* "test-group2" "a-project")))
    (is (some #{"test-group2"} (db/find-groupnames help/*db* name)))))

(deftest admins-can-be-added-to-groups
  (let [email "test@example.com"
        name "testadmin"
        password "password"]
    (db/add-user help/*db* email name password)
    (db/add-admin help/*db* "test-group" db/SCOPE-ALL name "some-dude")
    (is (= ["testadmin"] (db/group-activenames help/*db* "test-group")))
    (is (= [] (db/group-membernames help/*db* "test-group" db/SCOPE-ALL)))
    (is (= ["testadmin"] (db/group-adminnames help/*db* "test-group" db/SCOPE-ALL)))
    (is (some #{"test-group"} (db/find-groupnames help/*db* name)))

    (db/add-admin help/*db* "test-group2" "a-project" name "some-dude")
    (is (= ["testadmin"] (db/group-activenames help/*db* "test-group2")))
    (is (= [] (db/group-membernames help/*db* "test-group2" "a-project")))
    (is (= ["testadmin"] (db/group-adminnames help/*db* "test-group2" "a-project")))
    (is (= [] (db/group-adminnames help/*db* db/SCOPE-ALL "a-project")))
    (is (some #{"test-group2"} (db/find-groupnames help/*db* name)))))

;; TODO: Tests below should have the users added first.
;; Currently user unenforced foreign keys are by name
;; so these are faking relationships

(deftest added-jars-can-be-found
  (let [name "tester"
        created-at (Timestamp. 0)
        jarmap {:name name :group name :version "1.0"
                :description "An dog awesome and non-existent test jar."
                :homepage "http://clojars.org/"
                :authors ["Alex Osborne" "a little fish"]}
        result {:jar_name name
                :version "1.0"
                :homepage "http://clojars.org/"
                :scm nil
                :user "test-user"
                :created created-at
                :group_name name
                :authors "Alex Osborne, a little fish"
                :description "An dog awesome and non-existent test jar."}]
    (help/add-verified-group "test-user" name)
    (help/with-time created-at
      (db/add-jar help/*db* "test-user" jarmap)
      (are [x] (match? result x)
        (db/find-jar help/*db* name name)
        (first (db/jars-by-groupname help/*db* name))
        (first (db/jars-by-username help/*db* "test-user"))))))

(deftest jars-with-edn-values-are-properly-read
  (let [name "tester"
        jarmap {:name name :group name :version "1.0"
                :licenses [{:name "foo" :url "bar"}]
                :scm {:connection "ham" :url "biscuit"}}
        _ (help/add-verified-group "test-user" name)
        _ (db/add-jar help/*db* "test-user" jarmap)
        jar (db/find-jar help/*db* name name)]
    (is (= (:licenses jarmap) (:licenses jar)))
    (is (= (:scm jarmap) (:scm jar)))))

(deftest jars-with-improper-edn-values-cannot-be-written
  (let [name "tester"
        jarmap {:name name :group name :version "1.0"
                :licenses [{:name [:gotcha] :url "bar"}]}]
    (is (thrown? ExceptionInfo
          (db/add-jar help/*db* "test-user" jarmap)))
    (let [jarmap {:name name :group name :version "1.0"
                  :scm {:foo :bar}}]
      (is (thrown? ExceptionInfo
            (db/add-jar help/*db* "test-user" jarmap))))))

(deftest jars-with-improper-edn-values-are-properly-read
  ;; redef to allow us to put invalid data in the db
  (with-redefs [db/safe-pr-str pr-str]
    (let [name "tester"
          jarmap {:name name :group name :version "1.0"
                  :licenses [{:name [:gotcha] :url "bar"}]
                  :scm {:connection "ham" :url :boom}}
          _ (help/add-verified-group "test-user" name)
          _ (db/add-jar help/*db* "test-user" jarmap)
          jar (db/find-jar help/*db* name name)]
      (is (= name (:jar_name jar)))
      (is (nil? (:licenses jar)))
      (is (nil? (:scm jar))))))

(deftest added-jars-store-dependencies
  (let [name "tester"
        jarmap {:name name :group name :version "1.0"
                :description "An dog awesome and non-existent test jar."
                :homepage "http://clojars.org/"
                :authors ["Alex Osborne" "a little fish"]
                :dependencies [{:group_name "foo" :jar_name "bar" :version "1" :scope "test"}]}]
    (help/add-verified-group "test-user" name)
    (db/add-jar help/*db* "test-user" jarmap)
    (let [deps (db/find-dependencies help/*db* name name "1.0")]
      (is (= 1 (count deps)))
      (is (match?
           {:jar_name       name
            :group_name     name
            :version        "1.0"
            :dep_jar_name   "bar"
            :dep_group_name "foo"
            :dep_version    "1"
            :dep_scope      "test"}
           (first deps))))))

(deftest added-snapshot-jars-do-not-duplicate-dependencies
  (let [name "tester"
        jarmap {:name name :group name :version "1.0-SNAPSHOT"
                :description "An dog awesome and non-existent test jar."
                :homepage "http://clojars.org/"
                :authors ["Alex Osborne" "a little fish"]
                :dependencies [{:group_name "foo" :jar_name "bar" :version "1" :scope "test"}]}]
    (help/add-verified-group "test-user" name)
    (db/add-jar help/*db* "test-user" jarmap)
    (db/add-jar help/*db* "test-user" jarmap)
    (let [deps (db/find-dependencies help/*db* name name "1.0-SNAPSHOT")]
      (is (= 1 (count deps)))
      (is (match?
           {:jar_name       name
            :group_name     name
            :version        "1.0-SNAPSHOT"
            :dep_jar_name   "bar"
            :dep_group_name "foo"
            :dep_version    "1"
            :dep_scope      "test"}
           (first deps))))))

(deftest jars-can-be-deleted-by-group
  (let [group "foo"
        jar {:name "one" :group group :version "1.0"
             :description "An dog awesome and non-existent test jar."
             :homepage "http://clojars.org/"
             :authors ["Alex Osborne" "a little fish"]
             :dependencies [{:group_name "foo" :jar_name "bar" :version "1" :scope "test"}]}]
    (help/add-verified-group "test-user" group)
    (help/add-verified-group "test-user" "another")
    (db/add-jar help/*db* "test-user" jar)
    (db/add-jar help/*db* "test-user"
                (assoc jar
                       :name "two"))
    (db/add-jar help/*db* "test-user"
                (assoc jar
                       :group "another"))
    (is (= 2 (count (db/jars-by-groupname help/*db* group))))
    (db/delete-jars help/*db* group)
    (is (empty? (db/jars-by-groupname help/*db* group)))
    (is (empty? (db/find-dependencies help/*db* group "one" "1.0")))
    (is (= 1 (count (db/jars-by-groupname help/*db* "another"))))))

(deftest jars-can-be-deleted-by-group-and-jar-id
  (let [group "foo"
        jar {:name "one" :group group :version "1.0"
             :description "An dog awesome and non-existent test jar."
             :homepage "http://clojars.org/"
             :authors ["Alex Osborne" "a little fish"]
             :dependencies [{:group_name "foo" :jar_name "bar" :version "1" :scope "test"}]}]
    (help/add-verified-group "test-user" group)
    (db/add-jar help/*db* "test-user" jar)
    (db/add-jar help/*db* "test-user"
                (assoc jar
                       :name "two"))
    (is (= 2 (count (db/jars-by-groupname help/*db* group))))
    (db/delete-jars help/*db* group "one")
    (is (= 1 (count (db/jars-by-groupname help/*db* group))))
    (is (empty? (db/find-dependencies help/*db* group "one" "1.0")))))

(deftest jars-can-be-deleted-by-group-and-jar-id-and-version
  (let [group "foo"
        jar {:name "one" :group group :version "1.0"
             :description "An dog awesome and non-existent test jar."
             :homepage "http://clojars.org/"
             :authors ["Alex Osborne" "a little fish"]
             :dependencies [{:group_name "foo" :jar_name "bar" :version "1" :scope "test"}]}]
    (help/add-verified-group "test-user" group)
    (help/with-time (Timestamp. (long 0))
      (db/add-jar help/*db* "test-user" jar))
    (db/jars-by-groupname help/*db* group)
    (help/with-time (Timestamp. (long 1))
      (db/add-jar help/*db* "test-user"
                  (assoc jar
                         :version "2.0")))
    (db/jars-by-groupname help/*db* group)
    (is (= "2.0" (-> (db/jars-by-groupname help/*db* group) first :version)))
    (db/delete-jars help/*db* group "one" "2.0")
    (is (= "1.0" (-> (db/jars-by-groupname help/*db* group) first :version)))
    (is (empty? (db/find-dependencies help/*db* group "one" "2.0")))))

(deftest jars-by-group-only-returns-most-recent-version
  (let [name "tester"
        jarmap {:name name :group name :version "1"}
        result {:jar_name name
                :version "2"
                :user "test-user"
                :group_name name}]
    (help/add-verified-group "test-user" name)
    (help/with-time (Timestamp. 0)
      (db/add-jar help/*db* "test-user" jarmap)
      (help/with-time (Timestamp. 1)
        (db/add-jar help/*db* "test-user" (assoc jarmap :version "2"))))
    (let [jars (db/jars-by-groupname help/*db* name)]
      (dorun (map #(is (= %1 (select-keys %2 (keys %1)))) [result] jars))
      (is (= 1 (count jars))))))

(deftest jars-with-multiple-versions
  (let [name "tester"
        jarmap {:name name :group name :version "1"}]
    (help/add-verified-group "test-user" name)
    (help/with-time (Timestamp. 0)
      (db/add-jar help/*db* "test-user" jarmap))
    (help/with-time (Timestamp. 1)
      (db/add-jar help/*db* "test-user" (assoc jarmap :version "2")))
    (help/with-time (Timestamp. 2)
      (db/add-jar help/*db* "test-user" (assoc jarmap :version "3")))
    (help/with-time (Timestamp. 3)
      (db/add-jar help/*db* "test-user" (assoc jarmap :version "4-SNAPSHOT")))
    (is (= 4 (db/count-versions help/*db* name name)))
    (is (= ["4-SNAPSHOT" "3" "2" "1"]
           (map :version (db/recent-versions help/*db* name name))))
    (is (= ["4-SNAPSHOT"] (map :version (db/recent-versions help/*db* name name 1))))
    (is (= "3" (:version (db/find-jar help/*db* name name))))
    (is (= "4-SNAPSHOT" (:version (db/find-jar help/*db* name name "4-SNAPSHOT"))))))

(deftest jars-by-group-returns-all-jars-in-group
  (let [name "tester"
        jarmap {:name name :group name :version "1"}
        result {:jar_name name
                :version "1"
                :group_name name}]
    (help/add-verified-group "test-user" name)
    (help/add-verified-group "test-user2" "tester-group")
    (db/add-member help/*db* name db/SCOPE-ALL "test-user2" "some-user")
    (help/with-time (Timestamp. 0)
      (db/add-jar help/*db* "test-user" jarmap))
    (help/with-time (Timestamp. 1)
      (db/add-jar help/*db* "test-user" (assoc jarmap :name "tester2")))
    (help/with-time (Timestamp. 2)
      (db/add-jar help/*db* "test-user2" (assoc jarmap :name "tester3")))
    (help/with-time (Timestamp. 3)
      (db/add-jar help/*db* "test-user2" (assoc jarmap :group "tester-group")))
    (let [jars (db/jars-by-groupname help/*db* name)]
      (dorun (map #(is (match? %1 %2))
                  [result
                   (assoc result :jar_name "tester2")
                   (assoc result :jar_name "tester3")]
                  jars))
      (is (= 3 (count jars))))))

(deftest jars-by-user-only-returns-most-recent-version
  (let [name "tester"
        jarmap {:name name :group name :version "1"}
        result {:jar_name name
                :version "2"
                :user "test-user"
                :group_name name}]
    (help/add-verified-group "test-user" name)
    (help/with-time (Timestamp. 0)
      (db/add-jar help/*db* "test-user" jarmap))
    (help/with-time (Timestamp. 1)
      (db/add-jar help/*db* "test-user" (assoc jarmap :version "2")))
    (let [jars (db/jars-by-username help/*db* "test-user")]
      (dorun (map #(is (= %1 (select-keys %2 (keys %1)))) [result] jars))
      (is (= 1 (count jars))))))

(deftest jars-by-user-returns-all-jars-by-user
  (let [name "tester"
        jarmap {:name name :group name :version "1"}
        result {:jar_name name
                :user "test-user"
                :version "1"
                :group_name name}]
    (help/add-verified-group "test-user" name)
    (help/add-verified-group "test-user" "tester-group")
    (db/add-member help/*db* name db/SCOPE-ALL "test-user2" "some-user")
    (help/with-time (Timestamp. 0)
      (db/add-jar help/*db* "test-user" jarmap))
    (help/with-time (Timestamp. 1)
      (db/add-jar help/*db* "test-user" (assoc jarmap :name "tester2")))
    (help/with-time (Timestamp. 2)
      (db/add-jar help/*db* "test-user2" (assoc jarmap :name "tester3")))
    (help/with-time (Timestamp. 3)
      (db/add-jar help/*db* "test-user" (assoc jarmap :group "tester-group")))
    (let [jars (db/jars-by-username help/*db* "test-user")]
      (dorun (map #(is (match? %1 %2))
                  [result
                   (assoc result :jar_name "tester2")
                   (assoc result :group_name "tester-group")]
                  jars))
      (is (= 3 (count jars))))))

(deftest add-jar-validates-group-permissions
  (let [jarmap {:name "jar-name" :version "1" :group "group-name"}]
    (db/add-member help/*db* "group-name" db/SCOPE-ALL "some-user" "some-dude")
    (is (thrown? Exception (db/add-jar help/*db* "test-user" jarmap)))))

(deftest recent-jars-returns-6-most-recent-jars-only-most-recent-version
  (let [name "tester"
        jarmap {:name name :group name
                :description "An dog awesome and non-existent test jar."
                :homepage "http://clojars.org/"
                :authors ["Alex Osborne" "a little fish"]
                :version "1"}
        result {:user "test-user"
                :jar_name name
                :version "1"
                :homepage "http://clojars.org/"
                :scm nil
                :group_name name
                :authors "Alex Osborne, a little fish"
                :description "An dog awesome and non-existent test jar."}]
    (help/add-verified-group "test-user" name)
    (help/with-time (Timestamp. (long 1))
      (db/add-jar help/*db* "test-user" (assoc jarmap :name "1")))
    (help/with-time (Timestamp. (long 2))
      (db/add-jar help/*db* "test-user" (assoc jarmap :name "2")))
    (help/with-time (Timestamp. (long 3))
      (db/add-jar help/*db* "test-user" (assoc jarmap :name "3")))
    (help/with-time (Timestamp. (long 4))
      (db/add-jar help/*db* "test-user" (assoc jarmap :name "4")))
    (help/with-time (Timestamp. (long 5))
      (db/add-jar help/*db* "test-user" (assoc jarmap :version "5")))
    (help/with-time (Timestamp. (long 6))
      (db/add-jar help/*db* "test-user" (assoc jarmap :name "6")))
    (help/with-time (Timestamp. (long 7))
      (db/add-jar help/*db* "test-user" (assoc jarmap :version "7")))
    (dorun (map #(is (match? %1 %2))
                [(assoc result :version "7")
                 (assoc result :jar_name "6")
                 (assoc result :jar_name "4")
                 (assoc result :jar_name "3")
                 (assoc result :jar_name "2")]
                (db/recent-jars help/*db*)))))

(deftest browse-projects-finds-jars
  (help/add-verified-group "test-user" "jester")
  (help/add-verified-group "test-user" "tester")
  (help/with-time (Timestamp. (long 0))
    (db/add-jar help/*db* "test-user" {:name "rock" :group "jester" :version "0.1"})
    (db/add-jar help/*db* "test-user" {:name "rock" :group "tester" :version "0.1"}))
  (help/with-time (Timestamp. (long 1))
    (db/add-jar help/*db* "test-user" {:name "rock" :group "tester" :version "0.2"})
    (db/add-jar help/*db* "test-user" {:name "paper" :group "tester" :version "0.1"}))
  (help/with-time (Timestamp. (long 2))
    (db/add-jar help/*db* "test-user" {:name "scissors" :group "tester" :version "0.1"}))
  ;; tests group_name and jar_name ordering
  (is (=
       '({:version "0.1", :jar_name "rock", :group_name "jester"}
         {:version "0.1", :jar_name "paper", :group_name "tester"})
       (->>
        (db/browse-projects help/*db* 1 2)
        (map #(select-keys % [:group_name :jar_name :version])))))

  ;; tests version ordering and pagination
  (is (=
       '({:version "0.2", :jar_name "rock", :group_name "tester"}
         {:version "0.1", :jar_name "scissors", :group_name "tester"})
       (->>
        (db/browse-projects help/*db* 2 2)
        (map #(select-keys % [:group_name :jar_name :version]))))))

(deftest count-projects-works
  (help/add-verified-group "test-user" "jester")
  (help/add-verified-group "test-user" "tester")
  (db/add-jar help/*db* "test-user" {:name "rock" :group "jester" :version "0.1"})
  (db/add-jar help/*db* "test-user" {:name "rock" :group "tester" :version "0.1"})
  (db/add-jar help/*db* "test-user" {:name "rock" :group "tester" :version "0.2"})
  (db/add-jar help/*db* "test-user" {:name "paper" :group "tester" :version "0.1"})
  (db/add-jar help/*db* "test-user" {:name "scissors" :group "tester" :version "0.1"})
  (is (= (db/count-all-projects help/*db*) 4))
  (is (= (db/count-projects-before help/*db* "a") 0))
  (is (= (db/count-projects-before help/*db* "tester/rock") 2))
  (is (= (db/count-projects-before help/*db* "tester/rocks") 3))
  (is (= (db/count-projects-before help/*db* "z") 4)))

(deftest can-check-jar-exists
  (help/add-verified-group "test-user" "tester")
  (db/add-jar help/*db* "test-user" {:name "rock" :group "tester" :version "0.1"})
  (is (db/jar-exists help/*db* "tester" "rock"))
  (is (not (db/jar-exists help/*db* "tester" "paper"))))

(deftest deploy-token-creation-and-lookup
  (let [username "test-user"
        groupname "a-group"
        jarname "a-jar"
        tokenname "test-token"
        _ (db/add-user help/*db* "email@example.com" username "a-password")
        {:keys [token]} (db/add-deploy-token help/*db*
                                             username tokenname groupname jarname false nil)

        {:keys [expires_at group_name jar_name single_use token_hash]}
        (db/find-token-by-value help/*db* token)]
    (is (nil? expires_at))
    (is (= groupname group_name))
    (is (= jarname jar_name))
    (is (= "no" single_use))
    (is (= (-> token buddy.hash/sha256 buddy.codecs/bytes->hex)
           token_hash))

    (let [expires-at-time (db/get-time)
          {:keys [token]} (db/add-deploy-token
                           help/*db*
                           username tokenname groupname jarname true expires-at-time)
          {:keys [expires_at single_use]} (db/find-token-by-value help/*db* token)]
      (is (= expires-at-time expires_at))
      (is (= "yes" single_use)))))

;; TODO: recent-versions

(deftest group-settings
  ;; Test insert
  (db/set-group-mfa-required help/*db* "my-group" true)
  (is (true? (:require_mfa_to_deploy (db/get-group-settings help/*db* "my-group"))))
  ;; Test upsert
  (db/set-group-mfa-required help/*db* "my-group" false)
  (is (false? (:require_mfa_to_deploy (db/get-group-settings help/*db* "my-group")))))
