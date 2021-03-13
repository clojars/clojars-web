(ns clojars.unit.friend.github-test
  (:require
   [clojars.db :as db]
   [clojars.friend.github :refer [workflow]]
   ;; for mulitmethods
   [clojars.oauth.github]
   [clojars.oauth.service :as oauth-service]
   [clojars.remote-service :as remote-service]
   [clojars.test-helper :as help]
   [clojure.test :refer [deftest testing is join-fixtures use-fixtures]]))

(use-fixtures :each
  (join-fixtures
   [help/default-fixture
    help/with-clean-database]))

(defn handle-workflow [req]
  ((workflow (oauth-service/new-mock-oauth-service
              :github
              {:authorize-uri
               "https://github.com/login/oauth/authorize"})
             (remote-service/new-mock-remote-service)
             help/*db*)
   req))

(deftest test-authorization
  (testing "accessing the authorization url"
    (let [req {:uri "/oauth/github/authorize"}
          response (handle-workflow req)]

      (is (some? (re-matches #"https://github.com/login/oauth/authorize.*"
                             (-> response :headers (get "Location"))))))))

(defn- set-mock-responses
  [emails login]
  (remote-service/set-responder
   'get-emails
   (constantly emails))
  (remote-service/set-responder
   'get-user
   (constantly {:login login})))

(deftest test-callback
  (remote-service/with-mocking
    (testing "with a valid user"
      (db/add-user help/*db* "john.doe@example.org" "johndoe" "pwd12345")
      (set-mock-responses
       [{:email "john.doe@example.org"
         :primary true
         :verified true}]
       "jd")

      (let [req {:uri "/oauth/github/callback"
                 :params {:code "1234567890"}}
            response (handle-workflow req)

            {:keys [auth-provider identity provider-login username]} response]

        (is (= :github auth-provider))
        (is (= "johndoe" identity))
        (is (= "jd" provider-login))
        (is (= "johndoe" username))
        (is (db/find-group-verification help/*db* "com.github.jd"))
        (is (db/find-group-verification help/*db* "io.github.jd"))))

    (testing "with a valid user but group already exists"
      (db/add-admin help/*db* "com.github.johnd" "someone" "clojars")
      (set-mock-responses
       [{:email "john.doe@example.org"
         :primary true
         :verified true}]
       "johnd")

      (let [req {:uri "/oauth/github/callback"
                 :params {:code "1234567890"}}
            response (handle-workflow req)
            {:keys [auth-provider identity provider-login username]} response]

        (is (= :github auth-provider))
        (is (= "johndoe" identity))
        (is (= "johnd" provider-login))
        (is (= "johndoe" username))
        (is (not (db/find-group-verification help/*db* "com.github.johnd")))
        (is (db/find-group-verification help/*db* "io.github.johnd"))))

    (testing "with a valid user which the clojars email is not the primary one"
      (db/add-user help/*db* "jane.dot@example.org" "janedot" "pwd12345")
      (set-mock-responses
       [{:email "jane.dot@company.com"
         :primary true
         :verified true}
        {:email "jane.dot@example.org"
         :primary false
         :verified true}]
       "jd")

      (let [req {:uri "/oauth/github/callback"
                 :params {:code "1234567890"}}
            response (handle-workflow req)
            {:keys [auth-provider identity provider-login username]} response]

        (is (= :github auth-provider))
        (is (= "janedot" identity))
        (is (= "jd" provider-login))
        (is (= "janedot" username))))

    (testing "with a non existing e-mail"
      (set-mock-responses
       [{:email "foolano@example.org"
         :primary true
         :verified true}]
       "")
      (let [req {:uri "/oauth/github/callback"
                 :params {:code "1234567890"}}
            response (handle-workflow req)]

        (is (= (-> response :headers (get "Location")) "/register"))
        (is (= (:flash response) "None of your e-mails are registered"))))

    (testing "with a non verified e-mail"
      (set-mock-responses
       [{:email "foolano@example.org"
         :primary true
         :verified false}]
       "")
      (let [req {:uri "/oauth/github/callback"
                 :params {:code "1234567890"}}
            response (handle-workflow req)]

        (is (= (-> response :headers (get "Location")) "/login"))
        (is (= (:flash response) "No verified e-mail was found"))))

    (testing "with an error returned to the callback"
      (let [req {:uri "/oauth/github/callback"
                 :params {:error "access_denied"
                          :error_description "The user has denied your application access."
                          :error_uri "https://docs.github.com/apps/managing-oauth-apps/troubleshooting-authorization-request-errors/#access-denied"}}
            response (handle-workflow req)]

        (is (= (-> response :headers (get "Location")) "/login"))
        (is (= (:flash response) "You declined access to your account"))))))
