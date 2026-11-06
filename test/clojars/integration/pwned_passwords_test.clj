(ns clojars.integration.pwned-passwords-test
  "These tests find fields by their form name, check the current page by its
  path, and compare messages against the constants in the code, so they don't
  depend on page layout or copy."
  (:require
   [clojars.auth :as auth]
   [clojars.email :as email]
   [clojars.models.user :as user-m]
   ;; for defmethods
   [clojars.notifications.user]
   [clojars.test-helper :as help]
   [clojars.user-validations :as uv]
   [clojure.string :as str]
   [clojure.test :refer [deftest is use-fixtures]]
   [kerodon.core :refer [fill-in follow-redirect press session visit]]
   [kerodon.test :refer [has value?]]
   [net.cgrand.enlive-html :as enlive]))

(use-fixtures :each
  help/default-fixture
  help/with-clean-database
  help/run-test-app)

;; From https://docs.hcaptcha.com/#integration-testing-test-keys
(def ^:private valid-captcha "10000000-aaaa-bbbb-cccc-000000000001")

(defn- field
  [name]
  [(enlive/attr= :name name)])

(defn- submit
  "Fills in `fields` (form name -> value) and submits the page's form."
  [state fields]
  (-> (reduce-kv (fn [state name value] (fill-in state (field name) value))
                 state
                 fields)
      ;; The only form with a submit button on these pages is the main one
      (press enlive/any-node)))

(defn- path
  [state]
  (get-in state [:request :uri]))

(defn- text-of
  [state selector]
  (-> (->> (enlive/select (:enlive state) selector)
           (enlive/texts)
           (str/join " "))
      (str/replace #"\s+" " ")))

(defn- shows-error?
  [state message]
  (str/includes? (text-of state [:.error]) message))

(defn- shows-message?
  [state message]
  (str/includes? (text-of state [:body]) message))

(defn- logged-in?
  [state]
  (= 200 (get-in (visit state "/profile") [:response :status])))

(defn- add-user
  [password]
  (user-m/add-user help/*db* "fixture@example.org" "fixture" password)
  (user-m/mark-email-verified help/*db* "fixture"))

(defn- log-in
  [password]
  (-> (session (help/app))
      (visit "/login")
      (submit {"username" "fixture" "password" password})
      (follow-redirect)))

(deftest user-cannot-register-with-pwned-password
  (let [state (-> (session (help/app))
                  (visit "/register")
                  (submit {"email"              "fixture@example.org"
                           "username"           "fixture"
                           "password"           help/pwned-password
                           "confirm"            help/pwned-password
                           "h-captcha-response" valid-captcha}))]
    (is (shows-error? state uv/pwned-password-message))))

(deftest user-cannot-change-to-pwned-password
  (add-user "password1234")
  (let [state (-> (log-in "password1234")
                  (visit "/profile")
                  (submit {"current-password" "password1234"
                           "password"         help/pwned-password
                           "confirm"          help/pwned-password}))]
    (is (shows-error? state uv/pwned-password-message))))

(deftest user-cannot-reset-to-pwned-password
  (add-user "password1234")
  (let [reset-code (user-m/set-password-reset-code! help/*db* "fixture")
        state (-> (session (help/app))
                  (visit (str "/password-resets/" reset-code))
                  (submit {"password" help/pwned-password
                           "confirm"  help/pwned-password}))]
    (is (shows-error? state uv/pwned-password-message))))

(deftest login-with-pwned-password-clears-password
  ;; Simulates a password that was set before it appeared in a breach
  (add-user help/pwned-password)
  (email/expect-mock-emails 1)
  (let [state (log-in help/pwned-password)]
    (is (= "/forgot-password" (path state)))
    (is (shows-message? state auth/pwned-password-login-message))
    (has state (value? (field "email-or-username") "fixture"))
    (is (not (logged-in? state))))
  (is (not (user-m/has-password? (user-m/find-user help/*db* "fixture"))))
  (is (true? (email/wait-for-mock-emails)))
  (help/assert-email-user-footer [["fixture@example.org" "fixture"]]
                                 @email/mock-emails))

(deftest login-with-pwned-password-logs-out-every-session
  (add-user "password1234")
  (let [this-browser (log-in "password1234")
        other-browser (log-in "password1234")]
    (is (logged-in? this-browser))
    (is (logged-in? other-browser))
    ;; The password appears in a breach after these sessions were created
    (user-m/update-user help/*db* "fixture" "fixture@example.org" help/pwned-password)
    (let [state (-> this-browser
                    (visit "/login")
                    (submit {"username" "fixture" "password" help/pwned-password})
                    (follow-redirect))]
      (is (= "/forgot-password" (path state)))
      (is (not (logged-in? state)))
      (is (not (logged-in? other-browser))))))

(deftest pwned-password-check-runs-before-mfa
  (add-user help/pwned-password)
  (user-m/set-otp-secret-key! help/*db* "fixture")
  (user-m/enable-otp! help/*db* "fixture")
  (let [state (log-in help/pwned-password)]
    (is (= "/forgot-password" (path state)))
    (is (shows-message? state auth/pwned-password-login-message)))
  (is (not (user-m/has-password? (user-m/find-user help/*db* "fixture")))))
