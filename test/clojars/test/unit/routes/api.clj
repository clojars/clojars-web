(ns clojars.test.unit.routes.api
  (:require [clojars.db :as db]
            [clojure.java.jdbc :as jdbc]
            [clojars.test.test-helper :as help]
            [clojure.test :refer :all]
            [clojars.routes.api :as api]
            [korma.core :refer [exec-raw]]))

(use-fixtures :each help/default-fixture help/index-fixture)

(def jarname "tester")
(def jarmap {:name jarname :group jarname})

(defn add-jar [i version & {:as override}]
  (with-redefs [db/get-time (fn [] (java.sql.Timestamp. i))]
    (is (db/add-jar "test-user" (merge (assoc jarmap :version version) override)))))

(deftest only-release
  (add-jar 0 "0.1.0")
  (let [jars (api/jars-by-groupname jarname)]
    (is (= 1 (count jars)))
    (is (= "0.1.0" (:latest_release (first jars))))
    (is (= "0.1.0" (:latest_version (first jars))))))

(deftest latest-release
  (add-jar 0 "0.1.0")
  (add-jar 1 "0.2.0")
  (let [jars (api/jars-by-groupname jarname)]
    (is (= 1 (count jars)))
    (is (= "0.2.0" (:latest_release (first jars))))
    (is (= "0.2.0" (:latest_version (first jars))))))

(deftest only-snapshot
  (add-jar 0 "0.1.0-SNAPSHOT")
  (let [jars (api/jars-by-groupname jarname)]
    (is (= 1 (count jars)))
    (is (not (:latest_release (first jars))))
    (is (= "0.1.0-SNAPSHOT" (:latest_version (first jars))))))

(deftest newer-snapshot
  (add-jar 0 "0.1.0")
  (add-jar 1 "0.1.1-SNAPSHOT")
  (let [jars (api/jars-by-groupname jarname)]
    (is (= 1 (count jars)))
    (is (= "0.1.0" (:latest_release (first jars))))
    (is (= "0.1.1-SNAPSHOT" (:latest_version (first jars))))))

(deftest older-snapshot
  (add-jar 0 "0.1.0-SNAPSHOT")
  (add-jar 1 "0.1.0")
  (let [jars (api/jars-by-groupname jarname)]
    (is (= 1 (count jars)))
    (is (= "0.1.0" (:latest_release (first jars))))
    (is (= "0.1.0" (:latest_version (first jars))))))

(deftest same-jarname-multiple-groups
  (add-jar 0 "0.1.0")
  (add-jar 1 "0.1.0" :group "tester2")
  (let [jars (api/jars-by-groupname jarname)]
    (is (= 1 (count jars)))))
