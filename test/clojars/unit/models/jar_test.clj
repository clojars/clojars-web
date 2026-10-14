(ns clojars.unit.models.jar-test
  (:require
   [clojars.models.jar :as jar-m]
   [clojars.models.permission :as permission-m]
   [clojars.test-helper :as help]
   [clojure.test :refer [are deftest is use-fixtures]]
   [matcher-combinators.test])
  (:import
   (clojure.lang
    ExceptionInfo)
   (java.sql
    Timestamp)))

(use-fixtures :each
  help/with-clean-database)

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
      (jar-m/add-jar help/*db* "test-user" jarmap)
      (are [x] (match? result x)
        (jar-m/find-jar help/*db* name name)
        (first (jar-m/jars-by-groupname help/*db* name))
        (first (jar-m/jars-by-username help/*db* "test-user"))))))

(deftest jars-with-edn-values-are-properly-read
  (let [name "tester"
        jarmap {:name name :group name :version "1.0"
                :licenses [{:name "foo" :url "bar"}]
                :scm {:connection "ham" :url "biscuit"}}
        _ (help/add-verified-group "test-user" name)
        _ (jar-m/add-jar help/*db* "test-user" jarmap)
        jar (jar-m/find-jar help/*db* name name)]
    (is (= (:licenses jarmap) (:licenses jar)))
    (is (= (:scm jarmap) (:scm jar)))))

(deftest jars-with-improper-edn-values-cannot-be-written
  (let [name "tester"
        jarmap {:name name :group name :version "1.0"
                :licenses [{:name [:gotcha] :url "bar"}]}]
    (is (thrown? ExceptionInfo
          (jar-m/add-jar help/*db* "test-user" jarmap)))
    (let [jarmap {:name name :group name :version "1.0"
                  :scm {:foo :bar}}]
      (is (thrown? ExceptionInfo
            (jar-m/add-jar help/*db* "test-user" jarmap))))))

(deftest jars-with-improper-edn-values-are-properly-read
  ;; redef to allow us to put invalid data in the db
  (with-redefs [jar-m/safe-pr-str pr-str]
    (let [name "tester"
          jarmap {:name name :group name :version "1.0"
                  :licenses [{:name [:gotcha] :url "bar"}]
                  :scm {:connection "ham" :url :boom}}
          _ (help/add-verified-group "test-user" name)
          _ (jar-m/add-jar help/*db* "test-user" jarmap)
          jar (jar-m/find-jar help/*db* name name)]
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
    (jar-m/add-jar help/*db* "test-user" jarmap)
    (let [deps (jar-m/find-dependencies help/*db* name name "1.0")]
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
    (jar-m/add-jar help/*db* "test-user" jarmap)
    (jar-m/add-jar help/*db* "test-user" jarmap)
    (let [deps (jar-m/find-dependencies help/*db* name name "1.0-SNAPSHOT")]
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
    (jar-m/add-jar help/*db* "test-user" jar)
    (jar-m/add-jar help/*db* "test-user"
                   (assoc jar
                          :name "two"))
    (jar-m/add-jar help/*db* "test-user"
                   (assoc jar
                          :group "another"))
    (is (= 2 (count (jar-m/jars-by-groupname help/*db* group))))
    (jar-m/delete-jars help/*db* group)
    (is (empty? (jar-m/jars-by-groupname help/*db* group)))
    (is (empty? (jar-m/find-dependencies help/*db* group "one" "1.0")))
    (is (= 1 (count (jar-m/jars-by-groupname help/*db* "another"))))))

(deftest jars-can-be-deleted-by-group-and-jar-id
  (let [group "foo"
        jar {:name "one" :group group :version "1.0"
             :description "An dog awesome and non-existent test jar."
             :homepage "http://clojars.org/"
             :authors ["Alex Osborne" "a little fish"]
             :dependencies [{:group_name "foo" :jar_name "bar" :version "1" :scope "test"}]}]
    (help/add-verified-group "test-user" group)
    (jar-m/add-jar help/*db* "test-user" jar)
    (jar-m/add-jar help/*db* "test-user"
                   (assoc jar
                          :name "two"))
    (is (= 2 (count (jar-m/jars-by-groupname help/*db* group))))
    (jar-m/delete-jars help/*db* group "one")
    (is (= 1 (count (jar-m/jars-by-groupname help/*db* group))))
    (is (empty? (jar-m/find-dependencies help/*db* group "one" "1.0")))))

(deftest jars-can-be-deleted-by-group-and-jar-id-and-version
  (let [group "foo"
        jar {:name "one" :group group :version "1.0"
             :description "An dog awesome and non-existent test jar."
             :homepage "http://clojars.org/"
             :authors ["Alex Osborne" "a little fish"]
             :dependencies [{:group_name "foo" :jar_name "bar" :version "1" :scope "test"}]}]
    (help/add-verified-group "test-user" group)
    (help/with-time (Timestamp. (long 0))
      (jar-m/add-jar help/*db* "test-user" jar))
    (jar-m/jars-by-groupname help/*db* group)
    (help/with-time (Timestamp. (long 1))
      (jar-m/add-jar help/*db* "test-user"
                     (assoc jar
                            :version "2.0")))
    (jar-m/jars-by-groupname help/*db* group)
    (is (= "2.0" (-> (jar-m/jars-by-groupname help/*db* group) first :version)))
    (jar-m/delete-jars help/*db* group "one" "2.0")
    (is (= "1.0" (-> (jar-m/jars-by-groupname help/*db* group) first :version)))
    (is (empty? (jar-m/find-dependencies help/*db* group "one" "2.0")))))


(deftest jars-by-group-only-returns-most-recent-version
  (let [name "tester"
        jarmap {:name name :group name :version "1"}
        result {:jar_name name
                :version "2"
                :user "test-user"
                :group_name name}]
    (help/add-verified-group "test-user" name)
    (help/with-time (Timestamp. 0)
      (jar-m/add-jar help/*db* "test-user" jarmap)
      (help/with-time (Timestamp. 1)
        (jar-m/add-jar help/*db* "test-user" (assoc jarmap :version "2"))))
    (let [jars (jar-m/jars-by-groupname help/*db* name)]
      (dorun (map #(is (= %1 (select-keys %2 (keys %1)))) [result] jars))
      (is (= 1 (count jars))))))

(deftest jars-with-multiple-versions
  (let [name "tester"
        jarmap {:name name :group name :version "1"}]
    (help/add-verified-group "test-user" name)
    (help/with-time (Timestamp. 0)
      (jar-m/add-jar help/*db* "test-user" jarmap))
    (help/with-time (Timestamp. 1)
      (jar-m/add-jar help/*db* "test-user" (assoc jarmap :version "2")))
    (help/with-time (Timestamp. 2)
      (jar-m/add-jar help/*db* "test-user" (assoc jarmap :version "3")))
    (help/with-time (Timestamp. 3)
      (jar-m/add-jar help/*db* "test-user" (assoc jarmap :version "4-SNAPSHOT")))
    (is (= 4 (jar-m/count-versions help/*db* name name)))
    (is (= ["4-SNAPSHOT" "3" "2" "1"]
           (map :version (jar-m/recent-versions help/*db* name name))))
    (is (= ["4-SNAPSHOT"] (map :version (jar-m/recent-versions help/*db* name name 1))))
    (is (= "3" (:version (jar-m/find-jar help/*db* name name))))
    (is (= "4-SNAPSHOT" (:version (jar-m/find-jar help/*db* name name "4-SNAPSHOT"))))))

(deftest jars-by-group-returns-all-jars-in-group
  (let [name "tester"
        jarmap {:name name :group name :version "1"}
        result {:jar_name name
                :version "1"
                :group_name name}]
    (help/add-verified-group "test-user" name)
    (help/add-verified-group "test-user2" "tester-group")
    (permission-m/add-member help/*db* name permission-m/SCOPE-ALL "test-user2" "some-user")
    (help/with-time (Timestamp. 0)
      (jar-m/add-jar help/*db* "test-user" jarmap))
    (help/with-time (Timestamp. 1)
      (jar-m/add-jar help/*db* "test-user" (assoc jarmap :name "tester2")))
    (help/with-time (Timestamp. 2)
      (jar-m/add-jar help/*db* "test-user2" (assoc jarmap :name "tester3")))
    (help/with-time (Timestamp. 3)
      (jar-m/add-jar help/*db* "test-user2" (assoc jarmap :group "tester-group")))
    (let [jars (jar-m/jars-by-groupname help/*db* name)]
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
      (jar-m/add-jar help/*db* "test-user" jarmap))
    (help/with-time (Timestamp. 1)
      (jar-m/add-jar help/*db* "test-user" (assoc jarmap :version "2")))
    (let [jars (jar-m/jars-by-username help/*db* "test-user")]
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
    (permission-m/add-member help/*db* name permission-m/SCOPE-ALL "test-user2" "some-user")
    (help/with-time (Timestamp. 0)
      (jar-m/add-jar help/*db* "test-user" jarmap))
    (help/with-time (Timestamp. 1)
      (jar-m/add-jar help/*db* "test-user" (assoc jarmap :name "tester2")))
    (help/with-time (Timestamp. 2)
      (jar-m/add-jar help/*db* "test-user2" (assoc jarmap :name "tester3")))
    (help/with-time (Timestamp. 3)
      (jar-m/add-jar help/*db* "test-user" (assoc jarmap :group "tester-group")))
    (let [jars (jar-m/jars-by-username help/*db* "test-user")]
      (dorun (map #(is (match? %1 %2))
                  [result
                   (assoc result :jar_name "tester2")
                   (assoc result :group_name "tester-group")]
                  jars))
      (is (= 3 (count jars))))))

(deftest add-jar-validates-group-permissions
  (let [jarmap {:name "jar-name" :version "1" :group "group-name"}]
    (permission-m/add-member help/*db* "group-name" permission-m/SCOPE-ALL "some-user" "some-dude")
    (is (thrown? Exception (jar-m/add-jar help/*db* "test-user" jarmap)))))

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
      (jar-m/add-jar help/*db* "test-user" (assoc jarmap :name "1")))
    (help/with-time (Timestamp. (long 2))
      (jar-m/add-jar help/*db* "test-user" (assoc jarmap :name "2")))
    (help/with-time (Timestamp. (long 3))
      (jar-m/add-jar help/*db* "test-user" (assoc jarmap :name "3")))
    (help/with-time (Timestamp. (long 4))
      (jar-m/add-jar help/*db* "test-user" (assoc jarmap :name "4")))
    (help/with-time (Timestamp. (long 5))
      (jar-m/add-jar help/*db* "test-user" (assoc jarmap :version "5")))
    (help/with-time (Timestamp. (long 6))
      (jar-m/add-jar help/*db* "test-user" (assoc jarmap :name "6")))
    (help/with-time (Timestamp. (long 7))
      (jar-m/add-jar help/*db* "test-user" (assoc jarmap :version "7")))
    (dorun (map #(is (match? %1 %2))
                [(assoc result :version "7")
                 (assoc result :jar_name "6")
                 (assoc result :jar_name "4")
                 (assoc result :jar_name "3")
                 (assoc result :jar_name "2")]
                (jar-m/recent-jars help/*db*)))))

(deftest browse-projects-finds-jars
  (help/add-verified-group "test-user" "jester")
  (help/add-verified-group "test-user" "tester")
  (help/with-time (Timestamp. (long 0))
    (jar-m/add-jar help/*db* "test-user" {:name "rock" :group "jester" :version "0.1"})
    (jar-m/add-jar help/*db* "test-user" {:name "rock" :group "tester" :version "0.1"}))
  (help/with-time (Timestamp. (long 1))
    (jar-m/add-jar help/*db* "test-user" {:name "rock" :group "tester" :version "0.2"})
    (jar-m/add-jar help/*db* "test-user" {:name "paper" :group "tester" :version "0.1"}))
  (help/with-time (Timestamp. (long 2))
    (jar-m/add-jar help/*db* "test-user" {:name "scissors" :group "tester" :version "0.1"}))
  ;; tests group_name and jar_name ordering
  (is (=
       '({:version "0.1", :jar_name "rock", :group_name "jester"}
         {:version "0.1", :jar_name "paper", :group_name "tester"})
       (->>
        (jar-m/browse-projects help/*db* 1 2)
        (map #(select-keys % [:group_name :jar_name :version])))))

  ;; tests version ordering and pagination
  (is (=
       '({:version "0.2", :jar_name "rock", :group_name "tester"}
         {:version "0.1", :jar_name "scissors", :group_name "tester"})
       (->>
        (jar-m/browse-projects help/*db* 2 2)
        (map #(select-keys % [:group_name :jar_name :version]))))))


(deftest count-projects-works
  (help/add-verified-group "test-user" "jester")
  (help/add-verified-group "test-user" "tester")
  (jar-m/add-jar help/*db* "test-user" {:name "rock" :group "jester" :version "0.1"})
  (jar-m/add-jar help/*db* "test-user" {:name "rock" :group "tester" :version "0.1"})
  (jar-m/add-jar help/*db* "test-user" {:name "rock" :group "tester" :version "0.2"})
  (jar-m/add-jar help/*db* "test-user" {:name "paper" :group "tester" :version "0.1"})
  (jar-m/add-jar help/*db* "test-user" {:name "scissors" :group "tester" :version "0.1"})
  (is (= 4 (jar-m/count-all-projects help/*db*)))
  (is (= 0 (jar-m/count-projects-before help/*db* "a")))
  (is (= 2 (jar-m/count-projects-before help/*db* "tester/rock")))
  (is (= 3 (jar-m/count-projects-before help/*db* "tester/rocks")))
  (is (= 4 (jar-m/count-projects-before help/*db* "z"))))

(deftest can-check-jar-exists
  (help/add-verified-group "test-user" "tester")
  (jar-m/add-jar help/*db* "test-user" {:name "rock" :group "tester" :version "0.1"})
  (is (jar-m/jar-exists help/*db* "tester" "rock"))
  (is (not (jar-m/jar-exists help/*db* "tester" "paper"))))
