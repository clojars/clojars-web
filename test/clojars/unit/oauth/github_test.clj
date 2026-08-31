(ns clojars.unit.oauth.github-test
  (:require
   [clojars.db :as db]
   ;; for mulitmethods
   [clojars.oauth.github]
   [clojars.remote-service :as remote-service]
   [clojars.test-helper :as help]
   [clojure.test :refer [deftest testing is use-fixtures]]
   [matcher-combinators.test]
   [ring.mock.request :refer [request]]))

(use-fixtures :each
  help/default-fixture
  help/with-clean-database
  help/run-test-app)

(deftest test-authorization
  (testing "accessing the authorization url"
    (let [app (help/app)
          response (app (request :get "/oauth/github/authorize"))]

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
    (let [app (help/app)]
      (testing "with a valid user"
        (db/add-user help/*db* "john.doe@example.org" "johndoe" "pwd12345")
        (set-mock-responses
         [{:email "john.doe@example.org"
           :primary true
           :verified true}]
         "jd")
        (let [response (app (request :get "/oauth/github/callback"
                                     {:code "1234567890"}))]
          (is (match?
               {:status 302
                :headers {"Location" "/"}}
               response))
          (is (db/find-group-verification help/*db* "com.github.jd"))
          (is (db/find-group-verification help/*db* "io.github.jd"))))
      (testing "with a valid upcased user"
        (db/add-user help/*db* "john.doe2@example.org" "johndoe2" "pwd12345")
        (set-mock-responses
         [{:email "john.doe2@example.org"
           :primary true
           :verified true}]
         "Jd2")
        (let [response (app (request :get "/oauth/github/callback"
                                     {:code "1234567890"}))]
          (is (match?
               {:status 302
                :headers {"Location" "/"}}
               response))
          (is (db/find-group-verification help/*db* "com.github.jd2"))
          (is (db/find-group-verification help/*db* "io.github.jd2"))))
      (testing "with a valid upcased email"
        (db/add-user help/*db* "john.doe3@example.org" "johndoe3" "pwd12345")
        (set-mock-responses
         [{:email "John.doe3@example.org"
           :primary true
           :verified true}]
         "jd3")
        (let [response (app (request :get "/oauth/github/callback"
                                     {:code "1234567890"}))]
          (is (match?
               {:status 302
                :headers {"Location" "/"}}
               response))
          (is (db/find-group-verification help/*db* "com.github.jd3"))
          (is (db/find-group-verification help/*db* "io.github.jd3"))))
      (testing "with a valid user but group already exists"
        (db/add-admin help/*db* "com.github.johnd" db/SCOPE-ALL "someone" "clojars")
        (set-mock-responses
         [{:email "john.doe@example.org"
           :primary true
           :verified true}]
         "johnd")
        (let [response (app (request :get "/oauth/github/callback"
                                     {:code "1234567890"}))]
          (is (match?
               {:status 302
                :headers {"Location" "/"}}
               response))
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
        (let [response (app (request :get "/oauth/github/callback"
                                     {:code "1234567890"}))]
          (is (match?
               {:status 302
                :headers {"Location" "/"}}
               response))))
      (testing "with a non existing e-mail"
        (set-mock-responses
         [{:email "foolano@example.org"
           :primary true
           :verified true}]
         "")
        (let [response (app (request :get "/oauth/github/callback"
                                     {:code "1234567890"}))]
          (is (= "/register" (-> response :headers (get "Location"))))
          (is (= "No account emails match the verified emails we got from GitHub" (:flash response)))))
      (testing "with a non verified e-mail"
        (set-mock-responses
         [{:email "foolano@example.org"
           :primary true
           :verified false}]
         "")
        (let [response (app (request :get "/oauth/github/callback"
                                     {:code "1234567890"}))]
          (is (= "/login" (-> response :headers (get "Location"))))
          (is (= "No verified emails were found in your GitHub account" (:flash response)))))
      (testing "with an error returned to the callback"
        (let [response (app (request :get "/oauth/github/callback"
                                     {:error "access_denied"
                                      :error_description "The user has denied your application access."
                                      :error_uri "https://docs.github.com/apps/managing-oauth-apps/troubleshooting-authorization-request-errors/#access-denied"}))]
          (is (= "/login" (-> response :headers (get "Location"))))
          (is (= "You declined access to your GitHub account" (:flash response))))))))
