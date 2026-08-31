(ns clojars.unit.oauth.gitlab-test
  (:require
   [clojars.db :as db]
   ;; for mulitmethods
   [clojars.oauth.gitlab]
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
          response (app (request :get "/oauth/gitlab/authorize"))]

      (is (some? (re-matches #"https://gitlab.com/oauth/authorize.*"
                             (-> response :headers (get "Location"))))))))

(defn- set-mock-responses
  [email username]
  (remote-service/set-responder
   'get-user
   (constantly {:email email
                :username username})))

(deftest test-callback
  (remote-service/with-mocking
    (let [app (help/app)]
      (testing "with a valid user"
        (db/add-user help/*db* "john.doe@example.org" "johndoe" "pwd12345")
        (set-mock-responses "john.doe@example.org" "jd")
        (let [response (app (request :get "/oauth/gitlab/callback"
                                     {:code "1234567890"}))]
          (is (match?
               {:status 302
                :headers {"Location" "/"}}
               response))
          (is (db/find-group-verification help/*db* "com.gitlab.jd"))
          (is (db/find-group-verification help/*db* "io.gitlab.jd"))))
      (testing "with a valid upcased user"
        (db/add-user help/*db* "john.doe2@example.org" "johndoe2" "pwd12345")
        (set-mock-responses "john.doe2@example.org" "Jd2")
        (let [response (app (request :get "/oauth/gitlab/callback"
                                     {:code "1234567890"}))]
          (is (match?
               {:status 302
                :headers {"Location" "/"}}
               response))
          (is (db/find-group-verification help/*db* "com.gitlab.jd2"))
          (is (db/find-group-verification help/*db* "io.gitlab.jd2"))))
      (testing "with a valid user but group already exists"
        (db/add-admin help/*db* "com.gitlab.johnd" db/SCOPE-ALL "someone" "clojars")
        (set-mock-responses "john.doe@example.org" "johnd")
        (let [response (app (request :get "/oauth/gitlab/callback"
                                     {:code "1234567890"}))]
          (is (match?
               {:status 302
                :headers {"Location" "/"}}
               response))
          (is (not (db/find-group-verification help/*db* "com.gitlab.johnd")))
          (is (db/find-group-verification help/*db* "io.gitlab.johnd"))))
      (testing "with a non existing e-mail"
        (set-mock-responses "foolano@example.org" "")
        (let [response (app (request :get "/oauth/gitlab/callback"
                                     {:code "1234567890"}))]
          (is (= "/register" (-> response :headers (get "Location"))))
          (is (= "No account emails match the verified emails we got from GitLab. Note: your Clojars email must be your primary email in GitLab, since the GitLab API does't provide a way to get verified secondary emails."
                 (:flash response)))))
      ;; TODO: (toby) fix this to use actual error response from gitlab
      (testing "with an error returned to the callback"
        (let [response (app (request :get "/oauth/gitlab/callback"
                                     {:error "access_denied"
                                      :error_description "The user has denied your application access."
                                      :error_uri "https://docs.gitlab.com/apps/managing-oauth-apps/troubleshooting-authorization-request-errors/#access-denied"}))]
          (is (= "/login" (-> response :headers (get "Location"))))
          (is (= "You declined access to your GitLab account" (:flash response))))))))
