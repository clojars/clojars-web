(ns clojars.integration.users-test
  (:require
   [clojars.email :as email]
   [clojars.integration.steps :refer [disable-mfa enable-mfa login-as register-as]]
   ;; for defmethods
   [clojars.notifications.user]
   [clojars.test-helper :as help]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [kerodon.core :refer [fill-in follow follow-redirect
                         press session visit within]]
   [kerodon.test :refer [has status? text? value?]]
   [net.cgrand.enlive-html :as enlive]))

(use-fixtures :each
  help/default-fixture
  help/with-clean-database
  help/run-test-app)

(deftest user-can-register
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password")
      (follow-redirect)
      (has (status? 200))
      (within [:div.light-article :> :h1]
        (has (text? "Dashboard (dantheman)")))))

(deftest bad-registration-info-should-show-error
  (-> (session (help/app))
      (register-as "fixture" "fixture@example.org" "password"))
  (-> (session (help/app))
      (visit "/")
      (follow "register")
      (has (status? 200))
      (within [:title]
        (has (text? "Register - Clojars")))

      (fill-in "Email" "test@example.org")
      (fill-in "Username" "dantheman")
      (press "Register")
      (has (status? 200))
      (within [:div.error :ul :li]
        (has (text? "Password can't be blankPassword must be 8 characters or longer")))

      (fill-in "Email" "test@example.org")
      (fill-in "Username" "dantheman")
      (fill-in "Password" (apply str (range 123)))
      (fill-in "Confirm password" (apply str (range 123)))
      (press "Register")
      (has (status? 200))
      (within [:div.error :ul :li]
        (has (text? "Password must be 256 or fewer characters")))

      (fill-in "Password" "password")
      (fill-in "Email" "test@example.com")
      (fill-in "Username" "dantheman")
      (press "Register")
      (has (status? 200))
      (has (value? [:input#username] "dantheman"))
      (has (value? [:input#email] "test@example.com"))
      (within [:div.error :ul :li]
        (has (text? "Password and confirm password must match")))

      (fill-in "Email" "")
      (fill-in "Username" "dantheman")
      (fill-in "Password" "password")
      (fill-in "Confirm password" "password")
      (press "Register")
      (has (status? 200))
      (within [:div.error :ul :li]
        (has (text? "Email can't be blankEmail must have an @ sign and a domain")))

      (fill-in "Email" "test@example.org")
      (fill-in "Username" "")
      (fill-in "Password" "password")
      (fill-in "Confirm password" "password")
      (press "Register")
      (has (status? 200))
      (within [:div.error :ul :li]
        (has (text? "Username must consist only of lowercase letters, numbers, hyphens and underscores.Username can't be blank")))
      (fill-in "Username" "<script>")
      (fill-in "Password" "password")
      (fill-in "Confirm password" "password")
      (press "Register")
      (has (status? 200))
      (within [:div.error :ul :li]
        (has (text? "Username must consist only of lowercase letters, numbers, hyphens and underscores.")))

      (fill-in "Username" "fixture")
      (fill-in "Password" "password")
      (fill-in "Confirm password" "password")
      (press "Register")
      (has (status? 200))
      (within [:div.error :ul :li]
        (has (text? "Username is already taken")))))

(deftest user-can-update-info
  (email/expect-mock-emails 3)
  (-> (session (help/app))
      (register-as "fixture" "fixture@example.org" "password")
      (follow-redirect)
      (follow "profile")
      (fill-in "Email" "fixture2@example.org")
      (fill-in "Current password" "password")
      (fill-in "New password" "password2")
      (fill-in "Confirm new password" "password2")
      (press "Update")
      (follow-redirect)
      (within [:div#notice]
        (has (text? "Profile updated.")))
      (follow "logout")
      (follow-redirect)
      (has (status? 200))
      (within [:nav [:li enlive/first-child] :a]
        (has (text? "login")))
      (login-as "fixture" "password2")
      (follow-redirect)
      (has (status? 200))
      (within [:div.light-article :> :h1]
        (has (text? "Dashboard (fixture)"))))
  (is (true? (email/wait-for-mock-emails)))
  (is (= #{"Your Clojars email was changed"
           "Your Clojars password was changed"}
         (into #{} (map second) @email/mock-emails))))

(deftest user-can-update-just-email
  (email/expect-mock-emails 2)
  (-> (session (help/app))
      (register-as "fixture" "fixture@example.org" "password")
      (follow-redirect)
      (follow "profile")
      (fill-in "Email" "fixture2@example.org")
      (fill-in "Current password" "password")
      (press "Update")
      (follow-redirect)
      (within [:div#notice]
        (has (text? "Profile updated."))))
  (is (true? (email/wait-for-mock-emails)))
  (let [[addresses titles bodies]
        (reduce
         #(map conj %1 %2)
         [#{} #{} #{}]
         @email/mock-emails)]
    (is (= #{"fixture@example.org" "fixture2@example.org"} addresses))
    (is (= #{"Your Clojars email was changed"} titles))
    (doseq [body bodies]
      (is (re-find #"from 'fixture@example.org' to 'fixture2@example.org'" body))
      (is (re-find #"Client IP" body)))))

(deftest user-can-update-just-password
  (email/expect-mock-emails 1)
  (-> (session (help/app))
      (register-as "fixture" "fixture@example.org" "password")
      (follow-redirect)
      (follow "profile")
      (fill-in "Current password" "password")
      (fill-in "New password" "password2")
      (fill-in "Confirm new password" "password2")
      (press "Update")
      (follow-redirect)
      (within [:div#notice]
        (has (text? "Profile updated.")))
      (follow "logout")
      (follow-redirect)
      (has (status? 200))
      (within [:nav [:li enlive/first-child] :a]
        (has (text? "login")))
      (login-as "fixture" "password2")
      (follow-redirect)
      (has (status? 200))
      (within [:div.light-article :> :h1]
        (has (text? "Dashboard (fixture)"))))
  (is (true? (email/wait-for-mock-emails)))
  (let [[address title body] (first @email/mock-emails)]
    (is (= "fixture@example.org" address))
    (is (= "Your Clojars password was changed" title))
    (is (re-find #"has changed the password on your 'fixture'" body))
    (is (re-find #"Client IP" body))))

(deftest bad-update-info-should-show-error
  (-> (session (help/app))
      (register-as "fixture" "fixture@example.org" "password")
      (follow-redirect)
      (follow "profile")
      (has (status? 200))
      (within [:title]
        (has (text? "Profile - Clojars")))

      (fill-in "Current password" "")
      (press "Update")
      (has (status? 200))
      (within [:div.error :ul :li]
        (has (text? "Current password can't be blankCurrent password is incorrect")))

      (fill-in "Current password" "wrong-password")
      (press "Update")
      (has (status? 200))
      (within [:div.error :ul :li]
        (has (text? "Current password is incorrect")))

      (fill-in "New password" "newpassword")
      (fill-in "Confirm new password" "newpassword")
      (press "Update")
      (has (status? 200))
      (within [:div.error :ul :li]
        (has (text? "Current password can't be blankCurrent password is incorrect")))

      (fill-in "Current password" "password")
      (fill-in "New password" "newpassword")
      (press "Update")
      (has (status? 200))
      (within [:div.error :ul :li]
        (has (text? "Password and confirm password must match")))

      (fill-in "Current password" "password")
      (fill-in "Email" "")
      (press "Update")
      (has (status? 200))
      (within [:div.error :ul :li]
        (has (text? "Email can't be blankEmail must have an @ sign and a domain")))))

(deftest user-can-get-new-password
  (email/expect-mock-emails 1)
  (-> (session (help/app))
      (register-as "fixture" "fixture@example.org" "password"))
  (-> (session (help/app))
      (visit "/")
      (follow "login")
      (follow "Forgot your username or password?")
      (fill-in "Email or Username" "fixture")
      (press "Email me a password reset link")
      (has (status? 200))
      (within [:p]
        (has (text? "If your account was found, you should get an email with a link to reset your password soon."))))
  (let [[to subject message :as email] (first @email/mock-emails)]
    (is email)
    (is (= to "fixture@example.org"))
    (is (= subject "Password reset for Clojars"))
    (let [password "some-secret!"
          [_ reset-password-link]
          (re-find
           #"Hello,\n\nWe received a request from someone, hopefully you, to reset the password of the clojars user: fixture.\n\nTo contine with the reset password process, click on the following link:\n\n([^ ]+)\n\n"
           message)]
      (is (re-find #"Client IP" message))
      (is (string? reset-password-link))
      (email/expect-mock-emails 1)
      (-> (session (help/app))
          (visit reset-password-link)
          (has (status? 200))
          (fill-in "New password" password)
          (fill-in "Confirm new password" password)
          (press "Update my password")
          (follow-redirect)
          (has (status? 200))
          (within [:div.small-section :> :h1]
            (has (text? "Login")))
          ;; can login with new password
          (login-as "fixture" password)
          (follow-redirect)
          (has (status? 200))
          (within [:div.light-article :> :h1]
            (has (text? "Dashboard (fixture)"))))))
  (is (true? (email/wait-for-mock-emails)))
  (let [[address title body] (first @email/mock-emails)]
    (is (= "fixture@example.org" address))
    (is (= "Your Clojars password was changed" title))
    (is (re-find #"has changed the password on your 'fixture'" body))
    (is (re-find #"Client IP" body))))

(deftest bad-reset-code-shows-message
  (-> (session (help/app))
      (visit "/password-resets/this-code-does-not-exist")
      (has (status? 200))
      (within [:p]
        (has (text? "The reset code was not found. Please ask for a new code in the forgot password page")))))

(deftest users-can-be-viewed
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password")
      (visit "/users/dantheman")
      (within [:div.light-article :> :h1]
        (has (text? "dantheman")))))

(deftest user-is-emailed-when-activating-and-deactivating-mfa
  (-> (session (help/app))
      (register-as "fixture" "fixture@example.org" "password"))
  (let [[otp-secret] (enable-mfa (session (help/app)) "fixture" "password")
        _ (is (true? (email/wait-for-mock-emails)))
        [address title body] (first @email/mock-emails)]
    (is (= "fixture@example.org" address))
    (is (= "Two-factor auth was enabled on your Clojars account" title))
    (is (re-find #"'fixture'" body))

    (testing "when manually disabled"
      (email/expect-mock-emails 1)
      (disable-mfa (session (help/app)) "fixture" "password" otp-secret)
      (is (true? (email/wait-for-mock-emails)))
      (let [[address title body] (first @email/mock-emails)]
        (is (= "fixture@example.org" address))
        (is (= "Two-factor auth was disabled on your Clojars account" title))
        (is (re-find #"'fixture'" body))
        (is (re-find #"manually disabled" body))
        (is (re-find #"Client IP" body))))

    (testing "when recovery code used"
      (email/expect-mock-emails 2)
      (let [[_otp-secret recovery-code] (enable-mfa (session (help/app)) "fixture" "password")]
        (login-as (session (help/app)) "fixture" "password" recovery-code)
        ;; wait for the enable then recovery emails to be sent
        (is (true? (email/wait-for-mock-emails)))
        (let [[address title body] (second @email/mock-emails)]
          (is (= "fixture@example.org" address))
          (is (= "Two-factor auth was disabled on your Clojars account" title))
          (is (re-find #"'fixture'" body))
          (is (re-find #"your recovery code" body)))))))
