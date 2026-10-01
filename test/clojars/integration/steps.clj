(ns clojars.integration.steps
  (:require
   [cemerick.pomegranate.aether :as aether]
   [clojars.config :as config :refer [config]]
   [clojars.db :as db]
   [clojars.email :as email]
   [clojars.maven :as maven]
   [clojars.test-helper :as help]
   [clojure.java.io :as io]
   [kerodon.core :refer [check choose fill-in follow follow-redirect press visit]]
   [net.cgrand.enlive-html :as enlive]
   [one-time.core :as ot])
  (:import
   java.io.File))

(defn- test-db
  "Returns the test database connection, whether or not `help/*db*` is bound."
  []
  (if (bound? #'help/*db*)
    help/*db*
    (binding [config/*profile* "test"]
      (:db (config)))))

(defn verify-email-for
  "Directly marks the named user's email as verified in the DB.
  Used in tests to bypass the email verification flow."
  [username]
  (db/mark-email-verified! (test-db) username))

(defn login-as
  ([state user password]
   (-> state
       (visit "/")
       (follow "login")
       (fill-in "Username" user)
       (fill-in "Password" password)
       (press "Login")))
  ([state user password otp]
   (-> state
       (login-as user password)
       (follow-redirect)
       (fill-in "Two-Factor Code" otp)
       (press "Continue"))))

(defn fill-in-captcha
  ([state]
   ;; From https://docs.hcaptcha.com/#integration-testing-test-keys
   (fill-in-captcha state "10000000-aaaa-bbbb-cccc-000000000001"))
  ([state value]
   (fill-in state "TEST Captcha" value)))

(defn register-as
  "Registers a user, verifies their email via direct DB write, then logs in.
  This is the normal test setup helper; it returns an authenticated session at
  the dashboard, exactly as registration did before the email-verification feature.
  The verification email is sent but then drained from the mock so it does not
  pollute tests that are not testing the email verification flow.
  Use register-unverified-as when you specifically want to test the verification flow."
  ([state user email password]
   (register-as state user email password password))
  ([state user email password confirm]
   (email/expect-mock-emails 1)
   (let [registered (-> state
                        (visit "/")
                        (follow "register")
                        (fill-in "Email" email)
                        (fill-in "Username" user)
                        (fill-in "Password" password)
                        (fill-in "Confirm password" confirm)
                        (fill-in-captcha)
                        (press "Register"))]
     (email/wait-for-mock-emails)
     ;; Reset the mock so the verification email doesn't interfere with subsequent
     ;; assertions in tests that don't care about email verification.
     (email/expect-mock-emails 0)
     (verify-email-for user)
     ;; Log in to establish an authenticated session (registration no longer auto-logs in).
     (-> registered
         (visit "/login")
         (fill-in "Username" user)
         (fill-in "Password" password)
         (press "Login")
         (follow-redirect)))))

(defn register-unverified-as
  "Registers a user WITHOUT verifying their email. Use this when testing the
  email verification flow itself."
  ([state user email password]
   (register-unverified-as state user email password password))
  ([state user email password confirm]
   (-> state
       (visit "/")
       (follow "register")
       (fill-in "Email" email)
       (fill-in "Username" user)
       (fill-in "Password" password)
       (fill-in "Confirm password" confirm)
       (fill-in-captcha)
       (press "Register"))))

(defn create-deploy-token
  ([state user password token-name]
   (create-deploy-token state user password token-name {}))
  ([state user password token-name {:keys [expires-in scope single-use?]}]
   (-> state
       (login-as user password)
       (follow-redirect)
       (follow "deploy tokens")
       (fill-in "Token name" token-name)
       (cond-> scope (choose "Token scope" scope))
       (cond-> single-use? (check "Single use?"))
       (cond-> expires-in (choose "Expires in" expires-in))
       (press "Create Token")
       :enlive
       (enlive/select [:div.new-token :> :pre])
       (first)
       (enlive/text))))

(defn enable-mfa
  [state user password]
  (let [state (-> state
                  (login-as user password)
                  (follow-redirect)
                  (visit "/mfa")
                  (fill-in "Password" password)
                  (press "Enable two-factor authentication"))
        otp-secret (-> state
                       :enlive
                       (enlive/select [:pre.mfa-key])
                       (first)
                       (enlive/text))
        otp (ot/get-totp-token otp-secret)
        recovery-code (-> state
                          (fill-in "Code" otp)
                          (press "Confirm code")
                          :enlive
                          (enlive/select [:div.new-token :> :pre])
                          (first)
                          (enlive/text))]
    [otp-secret recovery-code]))

(defn disable-mfa
  [state user password otp-secret]
  (-> state
      (login-as user password (ot/get-totp-token otp-secret))
      (follow-redirect)
      (visit "/mfa")
      (fill-in "Password" password)
      (press "Disable two-factor authentication")))

(defn file-repo [path]
  (str (.toURI (File. path))))

(defn inject-artifacts-into-repo! [db user jar pom]
  (let [pom-file (if (instance? java.io.File pom)
                   pom
                   (io/resource pom))
        jarmap   (maven/pom-to-map pom-file)]
    (help/add-verified-group user (:group jarmap))
    (db/add-jar db user jarmap)
    (aether/deploy :coordinates [(keyword (:group jarmap)
                                          (:name jarmap))
                                 (:version jarmap)]
                   :jar-file (io/resource jar)
                   :pom-file pom-file
                   :local-repo help/local-repo
                   :repository {"local" (file-repo (:repo (config)))})))
