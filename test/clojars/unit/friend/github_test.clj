(ns clojars.unit.friend.github-test
  (:require [clojure.test :refer [deftest testing is join-fixtures use-fixtures]]
            [clojars.friend.github :refer [workflow]]
            [clojars.github :as github]
            [clojars.db :as db]

            [clojars.test-helper :as help]))

(use-fixtures :each
  (join-fixtures
    [help/default-fixture
     help/with-clean-database]))

(defn handle-with-config [req config]
  ((workflow (github/new-mock-github-service config) help/*db*) req))

(deftest test-authorization
  (testing "accessing the authorization url"
    (let [req {:uri "/oauth/github/authorize"}
          response (handle-with-config req {})]

      (is (some? (-> response :headers (get "Location")))))))

(deftest test-callback
  (testing "with a valid user"
    (db/add-user help/*db* "john.doe@example.org" "johndoe" "pwd12345")

    (let [req {:uri "/oauth/github/callback"
               :params {:code "1234567890"}}
          config {:email [{:email "john.doe@example.org"
                           :primary true
                           :verified true}]}

          response (handle-with-config req config)]

      (is (= response {:identity "johndoe" :username "johndoe"}))))

  (testing "with a valid user which the clojars email is not the primary one"
    (db/add-user help/*db* "jane.dot@example.org" "janedot" "pwd12345")

    (let [req {:uri "/oauth/github/callback"
               :params {:code "1234567890"}}
          config {:email [{:email "jane.dot@company.com"
                           :primary true
                           :verified true}
                          {:email "jane.dot@example.org"
                           :primary false
                           :verified true}]}

          response (handle-with-config req config)]

      (is (= response {:identity "janedot" :username "janedot"}))))

  (testing "with a non existing e-mail"
    (let [req {:uri "/oauth/github/callback"
               :params {:code "1234567890"}}
          config {:email [{:email "foolano@example.org"
                           :primary true
                           :verified true}]}

          response (handle-with-config req config)]

      (is (= (-> response :headers (get "Location")) "/register"))
      (is (= (:flash response) "None of your e-mails are registered"))))

  (testing "with a non verified e-mail"
    (let [req {:uri "/oauth/github/callback"
               :params {:code "1234567890"}}
          config {:email [{:email "foolano@example.org"
                           :primary true
                           :verified false}]}

          response (handle-with-config req config)]

      (is (= (-> response :headers (get "Location")) "/login"))
      (is (= (:flash response) "No verified e-mail was found")))))
