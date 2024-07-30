(ns clojars.unit.friend.oauth.gitlab-test
  (:require
   [clojars.db :as db]
   [clojars.friend.oauth.gitlab :refer [workflow]]
   ;; for mulitmethods
   [clojars.oauth.gitlab]
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
              "GitLab"
              {:authorize-uri
               "https://gitlab.com/oauth/authorize"})
             (remote-service/new-mock-remote-service)
             help/*db*)
   req))

(deftest test-authorization
  (testing "accessing the authorization url"
    (let [req {:uri "/oauth/gitlab/authorize"}
          response (handle-workflow req)]

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
    (testing "with a valid user"
      (db/add-user help/*db* "john.doe@example.org" "johndoe" "pwd12345")
      (set-mock-responses "john.doe@example.org" "jd")

      (let [req {:uri "/oauth/gitlab/callback"
                 :params {:code "1234567890"}}
            response (handle-workflow req)

            {:keys [auth-provider identity provider-login username]} response]

        (is (= "GitLab" auth-provider))
        (is (= "johndoe" identity))
        (is (= "jd" provider-login))
        (is (= "johndoe" username))
        (is (db/find-group-verification help/*db* "com.gitlab.jd"))
        (is (db/find-group-verification help/*db* "io.gitlab.jd"))))

    (testing "with a valid upcased user"
      (db/add-user help/*db* "john.doe2@example.org" "johndoe2" "pwd12345")
      (set-mock-responses "john.doe2@example.org" "Jd2")

      (let [req {:uri "/oauth/gitlab/callback"
                 :params {:code "1234567890"}}
            response (handle-workflow req)

            {:keys [auth-provider identity provider-login username]} response]

        (is (= "GitLab" auth-provider))
        (is (= "johndoe2" identity))
        (is (= "Jd2" provider-login))
        (is (= "johndoe2" username))
        (is (db/find-group-verification help/*db* "com.gitlab.jd2"))
        (is (db/find-group-verification help/*db* "io.gitlab.jd2"))))

    (testing "with a valid user but group already exists"
      (db/add-admin help/*db* "com.gitlab.johnd" db/SCOPE-ALL "someone" "clojars")
      (set-mock-responses "john.doe@example.org" "johnd")

      (let [req {:uri "/oauth/gitlab/callback"
                 :params {:code "1234567890"}}
            response (handle-workflow req)
            {:keys [auth-provider identity provider-login username]} response]

        (is (= "GitLab" auth-provider))
        (is (= "johndoe" identity))
        (is (= "johnd" provider-login))
        (is (= "johndoe" username))
        (is (not (db/find-group-verification help/*db* "com.gitlab.johnd")))
        (is (db/find-group-verification help/*db* "io.gitlab.johnd"))))

    (testing "with a non existing e-mail"
      (set-mock-responses "foolano@example.org" "")
      (let [req {:uri "/oauth/gitlab/callback"
                 :params {:code "1234567890"}}
            response (handle-workflow req)]

        (is (= "/register" (-> response :headers (get "Location"))))
        (is (= "No account emails match the verified emails we got from GitLab. Note: your Clojars email must be your primary email in GitLab, since the GitLab API does't provide a way to get verified secondary emails."
               (:flash response)))))

    ;; TODO: (toby) fix this to use actual error response from gitlab
    (testing "with an error returned to the callback"
      (let [req {:uri "/oauth/gitlab/callback"
                 :params {:error "access_denied"
                          :error_description "The user has denied your application access."
                          :error_uri "https://docs.gitlab.com/apps/managing-oauth-apps/troubleshooting-authorization-request-errors/#access-denied"}}
            response (handle-workflow req)]

        (is (= "/login" (-> response :headers (get "Location"))))
        (is (= "You declined access to your GitLab account" (:flash response)))))))
