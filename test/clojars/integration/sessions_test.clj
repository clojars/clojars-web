(ns clojars.integration.sessions-test
  (:require
   [clojars.integration.steps :refer [create-deploy-token enable-mfa login-as register-as]]
   [clojars.test-helper :as help]
   [clojure.java.jdbc :as jdbc]
   [clojure.test :refer [deftest testing use-fixtures]]
   [kerodon
    [core :refer [follow follow-redirect session within]]
    [test :refer [has status? text?]]]
   [net.cgrand.enlive-html :as enlive]
   [one-time.core :as ot]))

(use-fixtures :each
  help/default-fixture
  help/with-clean-database)

(deftest user-cant-login-with-bad-user-pass-combo
  (-> (session (help/app))
      (login-as "fixture@example.org" "password")
      (follow-redirect)
      (has (status? 200))
      (within [:div :p.error]
              (has (text? "Incorrect username, password, or MFA code.Make sure that you are using your username, and not your email to log in.")))))

(deftest user-can-login-and-logout
  (let [app (help/app)]
    (-> (session app)
        (register-as "fixture" "fixture@example.org" "password"))
    (doseq [login ["fixture"]]
      (-> (session app)
          (login-as login "password")
          (follow-redirect)
          (has (status? 200))
          (within [:.light-article :> :h1]
                  (has (text? "Dashboard (fixture)")))
          (follow "logout")
          (follow-redirect)
          (has (status? 200))
          (within [:nav [:li enlive/first-child] :a]
                  (has (text? "login")))))))

(deftest user-cant-login-with-deploy-token
  (let [app (help/app)
        _ (-> (session app)
              (register-as "fixture" "fixture@example.org" "password"))
        token (create-deploy-token (session app) "fixture" "password" "testing")]
    (-> (session app)
        (login-as "fixture" token)
        (follow-redirect)
        (has (status? 200))
        (within [:div :p.error]
                (has (text? "Incorrect username, password, or MFA code."))))))

(deftest user-with-password-wipe-gets-message
  (let [app (help/app)]
    (-> (session app)
        (register-as "fixture" "fixture@example.org" "password"))
    (jdbc/db-do-commands help/*db*
                         "update users set password='' where \"user\" = 'fixture'")
    (-> (session app)
        (login-as "fixture" "password")
        (follow-redirect)
        (has (status? 200))
        (within [:div :p.error]
                (has (text? "Incorrect username, password, or MFA code."))))))

(deftest login-with-mfa
  (let [app (help/app)]
    (-> (session app)
        (register-as "fixture" "fixture@example.org" "password"))
    (let [[otp-secret recovery-code] (enable-mfa (session app) "fixture" "password")]
      (testing "with valid token"
        (-> (session app)
            (login-as "fixture" "password" (ot/get-totp-token otp-secret))
            (follow-redirect)
            (has (status? 200))
            (within [:.light-article :> :h1]
                    (has (text? "Dashboard (fixture)")))))
      (testing "with invalid token"
        (-> (session app)
            (login-as "fixture" "password" "1")
            (follow-redirect)
            (has (status? 200))
            (within [:div :p.error]
                    (has (text? "Incorrect username, password, or MFA code.")))))
      (testing "with recovery code"
        (-> (session app)
            (login-as "fixture" "password" recovery-code)
            (follow-redirect)
            (has (status? 200))
            (within [:.light-article :> :h1]
                    (has (text? "Dashboard (fixture)"))))
        ;; mfa is now disabled, so login w/o an otp works
        (-> (session app)
            (login-as "fixture" "password")
            (follow-redirect)
            (has (status? 200))
            (within [:.light-article :> :h1]
                    (has (text? "Dashboard (fixture)"))))))))
