(ns clojars.test.unit.db
  (:require [clojars.db :as db]
            [clojure.java.jdbc :as jdbc]
            [clojars.test.test-helper :as help]
            [clojure.test :refer :all]))

(use-fixtures :each help/default-fixture help/index-fixture)

(defn submap [s m]
  (every? (fn [[k v]] (= (m k) v)) s))

(deftest added-users-can-be-found
  (let [email "test@example.com"
        name "testuser"
        password "password"
        ssh-key "asdf"
        pgp-key "aoeu"
        ms (long 0)]
      (is (db/add-user email name password ssh-key pgp-key))
      (are [x] (submap {:email email
                        :user name
                        :ssh_key ssh-key}
                       x)
           (db/find-user name)
           (db/find-user-by-user-or-email name)
           (db/find-user-by-user-or-email email))))

(deftest user-does-not-exist
  (is (not (db/find-user-by-user-or-email "test2@example.com"))))

(deftest updated-users-can-be-found
  (let [email "test@example.com"
        name "testuser"
        password "password"
        ssh-key "asdf"
        pgp-key "aoeu"
        ms (long 0)
        email2 "test2@example.com"
        name2 "testuser2"
        password2 "password2"
        ssh-key2 "asdf2"
        pgp-key2 "aoeu2"]
    (with-redefs [db/get-time (fn [] (java.sql.Timestamp. ms))]
      ;;TODO: What should be done about the key-file?
      (is (db/add-user email name password ssh-key pgp-key))
      (with-redefs [db/get-time (fn [] (java.sql.Timestamp. (long 1)))]
        ;;TODO: What should be done about the key-file?
        (is (db/update-user name email2 name2 password2 ssh-key2 pgp-key2))
        (are [x] (submap {:email email2
                          :user name2
                          :ssh_key ssh-key2
                          :pgp_key pgp-key2
                          :created ms}
                         x)
             (db/find-user name2)
             (db/find-user-by-user-or-email name2)
             (db/find-user-by-user-or-email email2)))
      (is (not (db/find-user name))))))

(deftest added-users-are-added-only-to-their-org-clojars-group
  (let [email "test@example.com"
        name "testuser"
        password "password"
        ssh-key "asdf"
        pgp-key "aoeu"]
    ;;TODO: What should be done about the key-file?
    (is (db/add-user email name password ssh-key pgp-key))
    (is (= ["testuser"]
           (db/group-membernames (str "org.clojars." name))))
    (is (= ["org.clojars.testuser"]
           (db/find-groupnames name)))))

(deftest users-can-be-added-to-groups
  (let [email "test@example.com"
        name "testuser"
        password "password"
        ssh-key "asdf"
        pgp-key "aoeu"]
    ;;TODO: What should be done about the key-file?
    (db/add-user email name password ssh-key pgp-key)
    (db/add-member "test-group" name "some-dude")
    (is (= ["testuser"] (db/group-membernames "test-group")))
    (is (some #{"test-group"} (db/find-groupnames name)))))

;;TODO: Tests below should have the users added first.
;;Currently user unenforced foreign keys are by name
;;so these are faking relationships

(deftest added-jars-can-be-found
  (let [name "tester"
        ms (long 0)
        jarmap {:name name :group name :version "1.0"
                :description "An dog awesome and non-existent test jar."
                :homepage "http://clojars.org/"
                :authors ["Alex Osborne" "a little fish"]}
        result {:jar_name name
                :version "1.0"
                :homepage "http://clojars.org/"
                :scm nil
                :user "test-user"
                :created ms
                :group_name name
                :authors "Alex Osborne, a little fish"
                :description "An dog awesome and non-existent test jar."}]
    (with-redefs [db/get-time (fn [] (java.sql.Timestamp. ms))]
      (is (db/add-jar "test-user" jarmap))
      (are [x] (submap result x)
           (db/find-jar name name)
           (first (db/jars-by-groupname name))
           (first (db/jars-by-username "test-user"))))))

(deftest jars-by-group-only-returns-most-recent-version
  (let [name "tester"
        jarmap {:name name :group name :version "1" }
        result {:jar_name name
                :version "2"
                :user "test-user"
                :group_name name }]
    (with-redefs [db/get-time (fn [] (java.sql.Timestamp. 0))]
      (is (db/add-jar "test-user" jarmap))
      (with-redefs [db/get-time (fn [] (java.sql.Timestamp. 1))]
        (is (db/add-jar "test-user" (assoc jarmap :version "2")))))
    (let [jars (db/jars-by-groupname name)]
      (dorun (map #(is (submap %1 %2)) [result] jars))
      (is (= 1 (count jars))))))

(deftest jars-with-multiple-versions
  (let [name "tester"
        jarmap {:name name :group name :version "1" }]
    (with-redefs [db/get-time (fn [] (java.sql.Timestamp. 0))]
      (is (db/add-jar "test-user" jarmap)))
    (with-redefs [db/get-time (fn [] (java.sql.Timestamp. 1))]
      (is (db/add-jar "test-user" (assoc jarmap :version "2"))))
    (with-redefs [db/get-time (fn [] (java.sql.Timestamp. 2))]
      (is (db/add-jar "test-user" (assoc jarmap :version "3"))))
    (with-redefs [db/get-time (fn [] (java.sql.Timestamp. 3))]
      (is (db/add-jar "test-user" (assoc jarmap :version "4-SNAPSHOT"))))
    (is (= 4 (db/count-versions name name)))
    (is (= ["4-SNAPSHOT" "3" "2" "1"]
           (map :version (db/recent-versions name name))))
    (is (= ["4-SNAPSHOT"] (map :version (db/recent-versions name name 1))))
    (is (= "3" (:version (db/find-jar name name))))
    (is (= "4-SNAPSHOT" (:version (db/find-jar name name "4-SNAPSHOT"))))))

(deftest jars-by-group-returns-all-jars-in-group
  (let [name "tester"
        jarmap {:name name :group name :version "1" }
        result {:jar_name name
                :version "1"
                :group_name name }]
    (db/add-member name "test-user" "some-dude")
    (db/add-member "tester-group" "test-user2" "some-dude")
    (db/add-member name "test-user2" "some-dude")
    (with-redefs [db/get-time (fn [] (java.sql.Timestamp. 0))]
      (is (db/add-jar "test-user" jarmap)))
    (with-redefs [db/get-time (fn [] (java.sql.Timestamp. 1))]
      (is (db/add-jar "test-user" (assoc jarmap :name "tester2"))))
    (with-redefs [db/get-time (fn [] (java.sql.Timestamp. 2))]
      (is (db/add-jar "test-user2" (assoc jarmap :name "tester3"))))
    (with-redefs [db/get-time (fn [] (java.sql.Timestamp. 3))]
      (is (db/add-jar "test-user2" (assoc jarmap :group "tester-group"))))
    (let [jars (db/jars-by-groupname name)]
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
    (with-redefs [db/get-time (fn [] (java.sql.Timestamp. 0))]
      (is (db/add-jar "test-user" jarmap))
          (with-redefs [db/get-time (fn [] (java.sql.Timestamp. 1))]
            (is (db/add-jar "test-user" (assoc jarmap :version "2")))))
    (let [jars (db/jars-by-username "test-user")]
      (dorun (map #(is (submap %1 %2)) [result] jars))
      (is (= 1 (count jars))))))

(deftest jars-by-user-returns-all-jars-by-user
  (let [name "tester"
        jarmap {:name name :group name :version "1" }
        result {:jar_name name
                :user "test-user"
                :version "1"
                :group_name name }]
    (db/add-member name "test-user" "some-dude")
    (db/add-member "tester-group" "test-user" "some-dude")
    (db/add-member name "test-user2" "some-dude")
    (with-redefs [db/get-time (fn [] (java.sql.Timestamp. 0))]
      (is (db/add-jar "test-user" jarmap)))
    (with-redefs [db/get-time (fn [] (java.sql.Timestamp. 1))]
      (is (db/add-jar "test-user" (assoc jarmap :name "tester2"))))
    (with-redefs [db/get-time (fn [] (java.sql.Timestamp. 2))]
      (is (db/add-jar "test-user2" (assoc jarmap :name "tester3"))))
    (with-redefs [db/get-time (fn [] (java.sql.Timestamp. 3))]
      (is (db/add-jar "test-user" (assoc jarmap :group "tester-group"))))
    (let [jars (db/jars-by-username "test-user")]
      (dorun (map #(is (submap %1 %2))
                  [result
                   (assoc result :jar_name "tester2")
                   (assoc result :group_name "tester-group")]
                  jars))
      (is (= 3 (count jars))))))

(deftest add-jar-validates-jar-name-format
  (let [jarmap {:group "group-name" :version "1"}]
    (is (thrown? Exception (db/add-jar "test-user" jarmap)))
    (is (thrown? Exception (db/add-jar "test-user"
                                       (assoc jarmap :name "HI"))))
    (is (thrown? Exception (db/add-jar "test-user"
                                       (assoc jarmap :name "lein*"))))
    (is (thrown? Exception (db/add-jar "test-user"
                                       (assoc jarmap :name "lein="))))
    (is (thrown? Exception (db/add-jar "test-user"
                                       (assoc jarmap :name "lein>"))))
    (is (thrown? Exception (db/add-jar "test-user"
                                       (assoc jarmap :name "べ"))))
    (is (db/add-jar "test-user" (assoc jarmap :name "hi")))
    (is (db/add-jar "test-user" (assoc jarmap :name "hi-")))
    (is (db/add-jar "test-user" (assoc jarmap :name "hi_1...2")))))

(deftest add-jar-validates-group-name-format
  (let [jarmap {:name "jar-name" :version "1"}]
    (is (thrown? Exception (db/add-jar "test-user" jarmap)))
    (is (thrown? Exception (db/add-jar "test-user"
                                       (assoc jarmap :group "HI"))))
    (is (thrown? Exception (db/add-jar "test-user"
                                       (assoc jarmap :group "lein*"))))
    (is (thrown? Exception (db/add-jar "test-user"
                                       (assoc jarmap :group "lein="))))
    (is (thrown? Exception (db/add-jar "test-user"
                                       (assoc jarmap :group "lein>"))))
    (is (thrown? Exception (db/add-jar "test-user"
                                       (assoc jarmap :group "べ"))))
    (is (db/add-jar "test-user" (assoc jarmap :group "hi")))
    (is (db/add-jar "test-user" (assoc jarmap :group "hi-")))
    (is (db/add-jar "test-user" (assoc jarmap :group "hi_1...2")))))

(deftest add-jar-validates-group-name-is-not-reserved
  (let [jarmap {:name "jar-name" :version "1"}]
    (doseq [group db/reserved-names]
      (is (thrown? Exception (db/add-jar "test-user"
                                         (assoc jarmap :group group)))))))

(deftest add-jar-validates-group-permissions
    (let [jarmap {:name "jar-name" :version "1" :group "group-name"}]
      (db/add-member "group-name" "some-user" "some-dude")
      (is (thrown? Exception (db/add-jar "test-user" jarmap)))))


(deftest add-jar-creates-single-member-group-for-user
    (let [jarmap {:name "jar-name" :version "1" :group "group-name"}]
      (is (empty? (db/group-membernames "group-name")))
      (db/add-jar "test-user" jarmap)
      (is (= ["test-user"] (db/group-membernames "group-name")))
      (is (= ["group-name"]
             (db/find-groupnames "test-user")))))

(deftest recent-jars-returns-5-most-recent-jars-only-most-recent-version
  (let [name "tester"
        ms (long 0)
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
    (with-redefs [db/get-time (fn [] (java.sql.Timestamp. (long 1)))]
      (db/add-jar "test-user" (assoc jarmap :name "1")))
    (with-redefs [db/get-time (fn [] (java.sql.Timestamp. (long 2)))]
      (db/add-jar "test-user" (assoc jarmap :name "2")))
    (with-redefs [db/get-time (fn [] (java.sql.Timestamp. (long 3)))]
      (db/add-jar "test-user" (assoc jarmap :name "3")))
    (with-redefs [db/get-time (fn [] (java.sql.Timestamp. (long 4)))]
      (db/add-jar "test-user" (assoc jarmap :name "4")))
    (with-redefs [db/get-time (fn [] (java.sql.Timestamp. (long 5)))]
      (db/add-jar "test-user" (assoc jarmap :version "5")))
    (with-redefs [db/get-time (fn [] (java.sql.Timestamp. (long 6)))]
      (db/add-jar "test-user" (assoc jarmap :name "6")))
    (with-redefs [db/get-time (fn [] (java.sql.Timestamp. (long 7)))]
      (db/add-jar "test-user" (assoc jarmap :version "7"))
      (db/add-jar "test-user" (assoc jarmap :version "8")))
    (dorun (map #(is (submap %1 %2))
                [(assoc result :version "8")
                 (assoc result :jar_name "6")
                 (assoc result :jar_name "4")
                 (assoc result :jar_name "3")
                 (assoc result :jar_name "2")]
                (db/recent-jars)))))

(deftest browse-projects-finds-jars
  (db/add-jar "test-user" {:name "rock" :group "jester" :version "0.1"})
  (db/add-jar "test-user" {:name "rock" :group "tester" :version "0.1"})
  (db/add-jar "test-user" {:name "rock" :group "tester" :version "0.2"})
  (db/add-jar "test-user" {:name "paper" :group "tester" :version "0.1"})
  (db/add-jar "test-user" {:name "scissors" :group "tester" :version "0.1"})
    ; tests group_name and jar_name ordering
    (is (=
          '({:version "0.1", :jar_name "rock", :group_name "jester"}
            {:version "0.1", :jar_name "paper", :group_name "tester"})
          (->>
            (db/browse-projects 1 2)
            (map #(select-keys % [:group_name :jar_name :version])))))

    ; tests version ordering and pagination
    (is (=
          '({:version "0.2", :jar_name "rock", :group_name "tester"}
            {:version "0.1", :jar_name "scissors", :group_name "tester"})
          (->>
            (db/browse-projects 2 2)
            ( map #(select-keys % [:group_name :jar_name :version]))))))

(deftest count-projects-works
  (db/add-jar "test-user" {:name "rock" :group "jester" :version "0.1"})
  (db/add-jar "test-user" {:name "rock" :group "tester" :version "0.1"})
  (db/add-jar "test-user" {:name "rock" :group "tester" :version "0.2"})
  (db/add-jar "test-user" {:name "paper" :group "tester" :version "0.1"})
  (db/add-jar "test-user" {:name "scissors" :group "tester" :version "0.1"})
  (is (= (db/count-all-projects) 4))
  (is (= (db/count-projects-before "a") 0))
  (is (= (db/count-projects-before "tester/rock") 2))
  (is (= (db/count-projects-before "tester/rocks") 3))
  (is (= (db/count-projects-before "z") 4)))

;; TODO: search tests?
;; TODO: recent-versions
