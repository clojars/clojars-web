(ns clojars.test.unit.web.browse
  (:require [clj-time.core :as time]
            [clojars.db :as db]
            [clojars.test.test-helper :as help]
            [clojars.web.browse :refer :all]
            [clojure.test :refer :all]))

(use-fixtures :each
  help/using-test-config
  help/with-clean-database)

(deftest browse-projects-finds-jars
  (db/add-jar help/*db* "test-user" {:name "rock" :group "jester" :version "0.1"}
              (time/epoch))
  (db/add-jar help/*db* "test-user" {:name "rock" :group "tester" :version "0.1"}
              (time/epoch))
  (db/add-jar help/*db* "test-user" {:name "rock" :group "tester" :version "0.2"}
              (time/plus (time/epoch) (time/seconds 1)))
  (db/add-jar help/*db* "test-user" {:name "paper" :group "tester" :version "0.1"}
              (time/plus (time/epoch) (time/seconds 1)))
  (db/add-jar help/*db* "test-user" {:name "scissors" :group "tester" :version "0.1"}
              (time/plus (time/epoch) (time/seconds 2)))
    ; tests group_name and jar_name ordering
    (is (=
          '({:version "0.1", :jar_name "rock", :group_name "jester"}
            {:version "0.1", :jar_name "paper", :group_name "tester"})
          (->>
            (browse-projects help/*db* 1 2)
            (map #(select-keys % [:group_name :jar_name :version])))))

    ; tests version ordering and pagination
    (is (=
          '({:version "0.2", :jar_name "rock", :group_name "tester"}
            {:version "0.1", :jar_name "scissors", :group_name "tester"})
          (->>
            (browse-projects help/*db* 2 2)
            ( map #(select-keys % [:group_name :jar_name :version]))))))

