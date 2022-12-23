(ns clojars.integration.steps
  (:require
   [cemerick.pomegranate.aether :as aether]
   [clojars.config :refer [config]]
   [clojars.db :as db]
   [clojars.maven :as maven]
   [clojars.test-helper :as help]
   [clojure.java.io :as io]
   [kerodon.core :refer [check choose fill-in follow follow-redirect press visit]]
   [net.cgrand.enlive-html :as enlive]
   [one-time.core :as ot])
  (:import
   java.io.File))

(defn login-as
  ([state user password]
   (login-as state user password nil))
  ([state user password otp]
   (-> state
       (visit "/")
       (follow "login")
       (fill-in "Username" user)
       (fill-in "Password" password)
       (cond-> otp (fill-in "Two-Factor Code" otp))
       (press "Login"))))

(defn register-as
  ([state user email password]
   (register-as state user email password password))
  ([state user email password confirm]
   (-> state
       (visit "/")
       (follow "register")
       (fill-in "Email" email)
       (fill-in "Username" user)
       (fill-in "Password" password)
       (fill-in "Confirm password" confirm)
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
