(ns clojars.integration.steps
  (:require
   [cemerick.pomegranate.aether :as aether]
   [clojars.config :as config]
   [clojars.email :as email]
   [clojars.maven :as maven]
   [clojars.models.jar :as jar-m]
   [clojars.models.user :as user-m]
   [clojars.test-helper :as help]
   [clojure.java.io :as io]
   [kerodon.core :refer [check choose fill-in follow follow-redirect press visit]]
   [net.cgrand.enlive-html :as enlive]
   [one-time.core :as ot])
  (:import
   java.io.File))

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

(defn register-as
  ([state user email password]
   (register-as state user email password password))
  ([state user email password confirm]
   (email/expect-mock-emails 1)
   (let [registered (register-unverified-as state user email password confirm)]
     (email/wait-for-mock-emails)
     ;; Reset the mock so the verification email doesn't interfere with subsequent
     ;; assertions in tests that don't care about email verification.
     (email/clear-mock-emails)
     (user-m/mark-email-verified help/*db* user)
     (-> registered
         (visit "/login")
         (fill-in "Username" user)
         (fill-in "Password" password)
         (press "Login")
         (follow-redirect)))))

(defn create-deploy-token
  ([state user password token-name]
   (create-deploy-token state user password token-name {}))
  ([state user password token-name {:keys [expires-in scope single-use?]}]
   (email/expect-mock-emails 1)
   (let [state (-> state
                   (login-as user password)
                   (follow-redirect))]
     (email/wait-for-mock-emails)
     (email/clear-mock-emails)
     (email/expect-mock-emails 1)
     (-> state
         (follow "deploy tokens")
         (fill-in "Token name" token-name)
         (cond-> scope (choose "Token scope" scope))
         (cond-> single-use? (check "Single use?"))
         (cond-> expires-in (choose "Expires in" expires-in))
         (press "Create Token")
         :enlive
         (enlive/select [:div.new-token :> :pre])
         (first)
         (enlive/text)))))

(defn- enlive->text
  [state selector]
  (-> state
      :enlive
      (enlive/select selector)
      (first)
      (enlive/text)))

(defn- confirm-mfa-code
  [state otp-secret]
  (loop [state state
         attempt 1]
    (let [otp (ot/get-totp-token otp-secret)
          state (-> state
                    (fill-in "Code" otp)
                    (press "Confirm code"))]
      (if (and (< attempt 3)
               (re-find #"token incorrect" (enlive->text state [:div.notice])))
        (recur state (inc attempt))
        (enlive->text state [:div.new-token :> :pre])))))

(defn enable-mfa
  [state user password]
  (let [state (-> state
                  (login-as user password)
                  (follow-redirect)
                  (visit "/mfa")
                  (fill-in "Password" password)
                  (press "Enable two-factor authentication"))
        otp-secret (enlive->text state [:pre.mfa-key])
        recovery-code (confirm-mfa-code state otp-secret)]
    [otp-secret recovery-code]))

(defn login-as-with-otp+retry
  "Attempts login with otp, and retries twice on failure. This is to address the
  case where the otp code is no longer valid by the time we use it (we generated
  it close to the end of its lifetime)."
  [state user password otp-secret]
  (loop [attempt 1]
    (let [state (login-as state user password (ot/get-totp-token otp-secret))]
      (if (and (< attempt 3)
               (re-find #"Incorrect" (enlive->text (follow-redirect state) [:p.error])))
        (recur (inc attempt))
        state))))

(defn disable-mfa
  [state user password otp-secret]
  (-> state
      (login-as-with-otp+retry user password otp-secret)
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
    (jar-m/add-jar db user jarmap)
    (aether/deploy :coordinates [(keyword (:group jarmap)
                                          (:name jarmap))
                                 (:version jarmap)]
                   :jar-file (io/resource jar)
                   :pom-file pom-file
                   :local-repo help/local-repo
                   :repository {"local" (file-repo (:repo (config/config)))})))
