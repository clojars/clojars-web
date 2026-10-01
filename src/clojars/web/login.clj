(ns clojars.web.login
  (:require
   [clojars.web.common :refer [html-doc flash]]
   [clojars.web.helpers :as helpers]
   [clojars.web.safe-hiccup :refer [form-to]]
   [clojure.string :as str]
   [hiccup.element :refer [link-to]]
   [hiccup.form :refer [label text-field
                        password-field submit-button]]))


(defn login-form
  [login-failed username message]
  (html-doc
   "Login" {}
   [:div.small-section
    [:h1 "Login"]
    [:p.hint "Don't have an account? "
     (link-to "/register" "Sign up!")]
    (flash message)
    (when login-failed
      [:div
       [:p.error "Incorrect username or password."]
       (when (some? (str/index-of username \@))
         [:p.error "Make sure that you are using your username, and not your email to log in."])])
    (form-to [:post "/login" :class "row"]
             (label :username "Username")
             (text-field {:placeholder "bob"
                          :required true}
                         :username)
             (label :password "Password")
             (password-field {:placeholder "keep it secret, keep it safe"
                              :required true}
                             :password)
             (link-to {:class :hint-link} "/forgot-password" "Forgot your username or password?")

             (submit-button "Login")
             [:div#login-or "or"]
             [:div
              (link-to {:class "login-button github-login-button"}
                       "/oauth/github/authorize"
                       (helpers/retinized-image "/images/github-mark.png" "GitHub")
                       "Login with GitHub")]
             [:div
              (link-to {:class :login-button} "/oauth/gitlab/authorize"
                       (helpers/retinized-image "/images/gitlab-mark.png" "GitLab")
                       "Login with GitLab.com")])]))

(defn verify-email-pending-form
  "The holding page shown while a user's email is awaiting verification.
  `context` is :registration or :login; `email` is the address we sent the link to."
  [email context flash-msg]
  (html-doc
   "Verify your email address" {}
   [:div.small-section
    (flash flash-msg)
    [:h1 "Please verify your email address."]
    [:p (format "We sent a verification link to %s. Please check your inbox and click the link to continue." email)]
    (form-to [:post "/login/verify-email/resend"]
             (submit-button "Resend verification email"))]))

(defn email-verification-expired
  []
  (html-doc
   "Verification link expired" {}
   [:div.small-section
    [:h1 "Verification link expired"]
    [:p "This verification link has expired or is invalid. "
     "You can request a new one by "
     (link-to "/login" "logging in") "."]]))

(defn mfa-form
  [otp-failed message]
  (html-doc
   "Two-Factor Authentication" {}
   [:div.small-section
    [:h1 "Two-Factor Authentication"]
    [:p.hint "Enter the 6-digit code from your authenticator app, or your recovery code."]
    (flash message)
    (when otp-failed
      [:p.error "Incorrect two-factor code."])
    (form-to [:post "/login/mfa" :class "row"]
             (label :otp "Two-Factor Code")
             (text-field {:placeholder "123456"
                          :autocomplete "one-time-code"
                          :required true}
                         :otp)
             (submit-button "Continue"))]))
