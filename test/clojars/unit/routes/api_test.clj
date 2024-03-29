(ns clojars.unit.routes.api-test
  (:require
   [clojars.db :as db]
   [clojars.test-helper :as help]
   [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :each
  help/default-fixture
  help/with-clean-database)

(def jarname "tester")
(def jarmap {:name jarname :group jarname})

(defn add-jar [i version & {:as override}]
  (with-redefs [db/get-time (fn [] (java.sql.Timestamp. i))]
    (let [jarmap (merge (assoc jarmap :version version) override)]
      (help/add-verified-group "test-user" (:group jarmap))
      (db/add-jar help/*db* "test-user" jarmap))))


(deftest only-release
  (add-jar 0 "0.1.0")
  (doseq [args [[jarname] [jarname jarname]]]
    (let [jars (apply db/find-jars-information help/*db* args)]
      (is (= 1 (count jars)))
      (is (= "0.1.0" (:latest_release (first jars))))
      (is (= "0.1.0" (:latest_version (first jars)))))))

(deftest latest-release
  (add-jar 0 "0.1.0")
  (add-jar 1 "0.2.0")
  (doseq [args [[jarname] [jarname jarname]]]
    (let [jars (apply db/find-jars-information help/*db* args)]
      (is (= 1 (count jars)))
      (is (= "0.2.0" (:latest_release (first jars))))
      (is (= "0.2.0" (:latest_version (first jars)))))))

(deftest only-snapshot
  (add-jar 0 "0.1.0-SNAPSHOT")
  (doseq [args [[jarname] [jarname jarname]]]
    (let [jars (apply db/find-jars-information help/*db* args)]
      (is (= 1 (count jars)))
      (is (not (:latest_release (first jars))))
      (is (= "0.1.0-SNAPSHOT" (:latest_version (first jars)))))))

(deftest newer-snapshot
  (add-jar 0 "0.1.0")
  (add-jar 1 "0.1.1-SNAPSHOT")
  (doseq [args [[jarname] [jarname jarname]]]
    (let [jars (apply db/find-jars-information help/*db* args)]
      (is (= 1 (count jars)))
      (is (= "0.1.0" (:latest_release (first jars))))
      (is (= "0.1.1-SNAPSHOT" (:latest_version (first jars)))))))

(deftest older-snapshot
  (add-jar 0 "0.1.0-SNAPSHOT")
  (add-jar 1 "0.1.0")
  (doseq [args [[jarname] [jarname jarname]]]
    (let [jars (apply db/find-jars-information help/*db* args)]
      (is (= 1 (count jars)))
      (is (= "0.1.0" (:latest_release (first jars))))
      (is (= "0.1.0" (:latest_version (first jars)))))))

(deftest same-jarname-multiple-groups
  (add-jar 0 "0.1.0")
  (add-jar 1 "0.1.0" :group "tester2")
  (doseq [args [[jarname] [jarname jarname] ["tester2" jarname]]]
    (let [jars (apply db/find-jars-information help/*db* args)]
      (is (= 1 (count jars))))))

(deftest multiple-jars-in-same-group
  (add-jar 0 "0.1.0")
  (add-jar 0 "0.1.0" :name "other")
  (let [jars (db/find-jars-information help/*db* jarname)]
    (is (= 2 (count jars)))
    (is (= #{jarname "other"} (set (map :jar_name jars)))))
  (let [jars (db/find-jars-information help/*db* jarname jarname)]
    (is (= 1 (count jars)))
    (is (= jarname (-> jars first :jar_name)))))
