(ns clojars.integration.sessions-test
  (:require
   [clojars.db :as db]
   [clojars.email :as email]
   [clojars.integration.steps :refer [create-deploy-token enable-mfa login-as register-as
                                      register-unverified-as]]
   [clojars.test-helper :as help]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [kerodon.core :refer [follow follow-redirect press session visit within]]
   [kerodon.test :refer [has status? text?]]
   [net.cgrand.enlive-html :as enlive]
   [next.jdbc.sql :as sql]
   [one-time.core :as ot])
  (:import
   (java.util
    Date)))

(use-fixtures :each
  help/default-fixture
  help/with-clean-database
  help/run-test-app)

(deftest user-cant-login-with-bad-user-pass-combo
  (-> (session (help/app))
      (login-as "fixture@example.org" "password1234")
      (follow-redirect)
      (has (status? 200))
      (within [:div :p.error]
        (has (text? "Incorrect username or password.Make sure that you are using your username, and not your email to log in.")))))

(deftest user-can-login-and-logout
  (let [app (help/app)]
    (-> (session app)
        (register-as "fixture" "fixture@example.org" "password1234"))
    (doseq [login ["fixture"]]
      (-> (session app)
          (login-as login "password1234")
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
              (register-as "fixture" "fixture@example.org" "password1234"))
        token (create-deploy-token (session app) "fixture" "password1234" "testing")]
    (-> (session app)
        (login-as "fixture" token)
        (follow-redirect)
        (has (status? 200))
        (within [:div :p.error]
          (has (text? "Incorrect username or password."))))))

(deftest user-with-password-wipe-gets-message
  (let [app (help/app)]
    (-> (session app)
        (register-as "fixture" "fixture@example.org" "password1234"))
    (sql/update! help/*db* :users {:password ""} {db/user-column "fixture"})
    (-> (session app)
        (login-as "fixture" "password1234")
        (follow-redirect)
        (has (status? 200))
        (within [:div :p.error]
          (has (text? "Incorrect username or password."))))))

(deftest login-with-mfa
  (let [app (help/app)]
    (-> (session app)
        (register-as "fixture" "fixture@example.org" "password1234"))
    (let [[otp-secret recovery-code] (enable-mfa (session app) "fixture" "password1234")]
      (testing "with valid token"
        (-> (session app)
            (login-as "fixture" "password1234" (ot/get-totp-token otp-secret))
            (follow-redirect)
            (has (status? 200))
            (within [:.light-article :> :h1]
              (has (text? "Dashboard (fixture)")))))
      (testing "with a token that is too old"
        (let [the-past (Date. (- (System/currentTimeMillis) 31000))]
          (-> (session app)
              (login-as "fixture" "password1234" (ot/get-totp-token otp-secret {:date the-past}))
              (follow-redirect)
              (has (status? 200))
              (within [:p.error]
                (has (text? "Incorrect two-factor code."))))))
      (testing "with a token that is in the future"
        (let [the-future (Date. (+ (System/currentTimeMillis) 31000))]
          (-> (session app)
              (login-as "fixture" "password1234" (ot/get-totp-token otp-secret {:date the-future}))
              (follow-redirect)
              (has (status? 200))
              (within [:p.error]
                (has (text? "Incorrect two-factor code."))))))
      (testing "with invalid token"
        (-> (session app)
            (login-as "fixture" "password1234" "1")
            (follow-redirect)
            (has (status? 200))
            (within [:p.error]
              (has (text? "Incorrect two-factor code.")))))
      (testing "with recovery code"
        (-> (session app)
            (login-as "fixture" "password1234" recovery-code)
            (follow-redirect)
            (has (status? 200))
            (within [:.light-article :> :h1]
              (has (text? "Dashboard (fixture)"))))
        ;; mfa is now disabled, so login w/o an otp works
        (-> (session app)
            (login-as "fixture" "password1234")
            (follow-redirect)
            (has (status? 200))
            (within [:.light-article :> :h1]
              (has (text? "Dashboard (fixture)"))))))))

;; ---- Email Verification Tests ----

(deftest login-with-unverified-email-shows-verification-page
  ;; When a password-login user has not verified their email, login gates them.
  (let [app (help/app)]
    (db/add-user help/*db* "unverified@example.org" "unverified" "password1234")
    (email/expect-mock-emails 1)
    (-> (session app)
        (login-as "unverified" "password1234")
        (follow-redirect)
        (has (status? 200))
        (within [:div.small-section :> :h1]
          (has (text? "Please verify your email address."))))
    (is (true? (email/wait-for-mock-emails)))
    (let [[to subject] (first @email/mock-emails)]
      (is (= "unverified@example.org" to))
      (is (= "Confirm your Clojars email address" subject)))))

(deftest email-verification-link-verifies-and-redirects-to-login
  ;; Clicking the verification link marks the email verified and redirects to login.
  (let [app (help/app)]
    (db/add-user help/*db* "unverified@example.org" "unverified" "password1234")
    (email/expect-mock-emails 1)
    (-> (session app)
        (login-as "unverified" "password1234")
        (follow-redirect)
        (has (status? 200))
        (within [:div.small-section :> :h1]
          (has (text? "Please verify your email address."))))
    (is (true? (email/wait-for-mock-emails)))
    (let [[_ _ body] (first @email/mock-emails)
          [_ code] (re-find #"/email-verification/([a-f0-9]+)" body)]
      (is (string? code))
      (-> (session app)
          (visit (str "/email-verification/" code))
          (follow-redirect)
          (has (status? 200))
          (within [:div.small-section :> :h1]
            (has (text? "Login")))
          (within [:div#notice]
            (has (text? "Your email address has been confirmed. Please log in."))))
      (is (true? (:email_verified (db/find-user help/*db* "unverified")))))))

(deftest expired-verification-link-shows-error-page
  ;; An invalid or expired link shows the error page.
  (let [app (help/app)]
    (-> (session app)
        (visit "/email-verification/this-code-does-not-exist")
        (has (status? 200))
        (within [:div.small-section :> :h1]
          (has (text? "Verification link expired"))))))

(deftest resend-verification-email
  ;; The resend button sends a fresh verification email.
  (let [app (help/app)]
    (db/add-user help/*db* "unverified@example.org" "unverified" "password1234")
    (email/expect-mock-emails 1)
    (let [state (-> (session app)
                    (login-as "unverified" "password1234")
                    (follow-redirect))]
      (is (true? (email/wait-for-mock-emails)))
      (email/expect-mock-emails 1)
      (-> state
          (press "Resend verification email")
          (has (status? 200))
          (within [:div#notice]
            (has (text? "A new verification email has been sent."))))
      (is (true? (email/wait-for-mock-emails)))
      (let [[to subject] (first @email/mock-emails)]
        (is (= "unverified@example.org" to))
        (is (= "Confirm your Clojars email address" subject))))))

(deftest registration-redirects-to-email-verification-pending-page
  ;; After registration, the user is sent to the email verification pending page.
  (let [app (help/app)]
    (email/expect-mock-emails 1)
    (-> (session app)
        (register-unverified-as "newuser" "newuser@example.org" "password1234")
        (follow-redirect)
        (has (status? 200))
        (within [:div.small-section :> :h1]
          (has (text? "Welcome! Please verify your email address."))))
    (is (true? (email/wait-for-mock-emails)))
    (let [[to subject] (first @email/mock-emails)]
      (is (= "newuser@example.org" to))
      (is (= "Confirm your Clojars email address" subject)))))
