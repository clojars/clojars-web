(ns clojars.unit.verification-test
  (:require
   [clojars.db :as db]
   [clojars.test-helper :as help]
   [clojars.verification :as nut]
   [clojars.web.common :as common]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [are deftest is use-fixtures]]
   [matcher-combinators.test])
  (:import
   (java.util
    Date)))

(use-fixtures :each
  help/default-fixture
  help/with-clean-database)

(defn- TXT-vec->str
  [txt-records]
  (->> txt-records
       (map #(format "\"%s\"" %))
       (str/join "\n")))

(defmacro with-TXT
  [txt-records & body]
  `(with-redefs [shell/sh (constantly {:out  (TXT-vec->str ~txt-records)
                                       :exit 0})]
     ~@body))

(deftest test-group+domain-correspond?
  (are [exp group domain]
       (= exp (nut/group+domain-correspond? group domain))
    true  "com.foo"     "foo.com"
    true  "com.foo.bar" "foo.com"
    true  "com.foo"     "clojars.foo.com"
    true  "com.foo"     "_clojars.foo.com"
    true  "com.foo.bar" "clojars.foo.com"
    true  "com.foo.bar" "_clojars.foo.com"
    false "com.foo"     "foo.org"
    false "com.foo.bar" "foo.org"
    false "com.foo.bar" "clojars.foo.org"
    false "com.foo.bar" "_clojars.foo.org"
    false "foo"         "foo"
    false "foo."        ".foo"
    false ""            ""
    false nil           nil))

(deftest verify-group-by-TXT-with-invalid-domain
  (are [given] (= "The domain name is not a valid domain name."
                  (:error (nut/verify-group-by-TXT help/*db* {:group "com.foo"
                                                              :domain given})))
    "bar"
    "bar.com;rm -rf /"
    ".com"
    "..com"))

(deftest verify-group-by-TXT-with-invalid-group
  (are [given] (= "The group name is not a valid reverse-domain name."
                  (:error (nut/verify-group-by-TXT help/*db* {:group given
                                                              :domain "foo.com"})))
    "bar"
    "com.bar;rm -rf /"
    ".com"
    "..com"))

(deftest verify-group-by-TXT-with-no-correspondence
  (is (= "Group and domain do not correspond with each other."
         (:error (nut/verify-group-by-TXT help/*db* {:group "com.foo" :domain "bar.com"})))))

(deftest verify-group-by-TXT-with-invalid-TXT
  (doseq [records [["cloujars-abc"]
                   ["clojars+abc"]
                   ["other"
                    "records"]]]
    (with-TXT records
      (is (match?
           {:txt-records records
            :error "No valid verification TXT record found."}
           (nut/verify-group-by-TXT help/*db* {:group "com.foo" :domain "foo.com"}))))))

(deftest verify-group-by-TXT-with-TXT-pointing-to-different-user
  (let [records ["clojars abc"]]
    (with-TXT records
      (is (match?
           {:txt-records records
            :error       "Validation TXT record is for user 'abc', not 'dantheman' (you)."}
           (nut/verify-group-by-TXT help/*db* {:account "dantheman"
                                               :group   "com.foo"
                                               :domain  "foo.com"}))))))

(deftest verify-group-by-TXT-with-already-verified-group
  (let [records ["clojars dantheman"]]
    (db/verify-group! help/*db* "dantheman" "com.foo")
    (with-TXT records
      (is (match?
           {:txt-records records
            :error       (format "Group already verified by user 'dantheman' on %s."
                                 (common/format-date (Date.)))}
           (nut/verify-group-by-TXT help/*db* {:account "dantheman"
                                               :group   "com.foo"
                                               :domain  "foo.com"}))))))

(deftest verify-group-by-TXT-with-existing-group-user-is-not-active-member-of
  (let [records ["clojars dantheman"]]
    (db/add-group help/*db* "dantheman" "com.foo")
    ;; We have to have some other member for the group to be considered to
    ;; exist.
    (db/add-member help/*db* "com.foo" "anotheruser" "testing")
    (db/inactivate-member help/*db* "com.foo" "dantheman" "testing")
    (with-TXT records
      (is (match?
           {:txt-records records
            :error       "You are not an active member of the group."}
           (nut/verify-group-by-TXT help/*db* {:account "dantheman"
                                               :group   "com.foo"
                                               :domain  "foo.com"}))))))

(deftest verify-group-by-TXT-with-existing-group-user-is-not-member-of
  (let [records ["clojars dantheman"]]
    (db/add-group help/*db* "abc" "com.foo")
    (with-TXT records
      (is (match?
           {:txt-records records
            :error       "You are not an active member of the group."}
           (nut/verify-group-by-TXT help/*db* {:account "dantheman"
                                               :group   "com.foo"
                                               :domain  "foo.com"}))))))

(deftest verify-group-by-TXT-with-existing-group-user-is-active-member-of
  (let [records ["clojars dantheman"]]
    (db/add-group help/*db* "dantheman" "com.foo")
    (with-TXT records
      (is (match?
           {:message "The group 'com.foo' has been verified."}
           (nut/verify-group-by-TXT help/*db* {:account "dantheman"
                                               :group   "com.foo"
                                               :domain  "foo.com"}))))
    (is (db/find-group-verification help/*db* "com.foo"))))

(deftest verify-group-by-TXT-with-non-existing-group
  (let [records ["clojars dantheman"]]
    (with-TXT records
      (is (match?
           {:message "The group 'com.foo' has been verified."}
           (nut/verify-group-by-TXT help/*db* {:account "dantheman"
                                               :group   "com.foo"
                                               :domain  "foo.com"}))))
    (is (db/group-activenames help/*db* "com.foo"))
    (is (db/find-group-verification help/*db* "com.foo"))))
