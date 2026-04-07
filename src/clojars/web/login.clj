(ns clojars.web.login
  (:require
   [clojars.web.common :refer [flash form-to html-doc]]
   [clojars.web.helpers :as helpers]
   [clojure.string :as str]
   [hiccup.element :refer [link-to]]
   [hiccup.form :refer [label password-field submit-button text-field]]))

(defn login-form [login_failed username message]
  (html-doc "Login" {}
            [:div.small-section
             [:h1 "Login"]
             [:p.hint "Don't have an account? "
              (link-to "/register" "Sign up!")]

             (flash message)

             (when login_failed
               [:div
                [:p.error "Incorrect username, password, or two-factor code."]
                (when (some? (str/index-of username \@))
                  [:p.error "Make sure that you are using your username, and not your email to log in."])
                [:p.hint "If you have not logged in since April 2012 when "
                 [:a {:href "https://groups.google.com/group/clojure/browse_thread/thread/5e0d48d2b82df39b"}
                  "the insecure password hashes were wiped"]
                 ", please use the " [:a {:href "/forgot-password"} "forgot password"]
                 " functionality to reset your password."]])
             (form-to [:post "/login" :class "row"]
                      (label :username "Username")
                      (text-field {:placeholder "bob"
                                   :required true}
                                  :username)
                      (label :password "Password")
                      (password-field {:placeholder "keep it secret, keep it safe"
                                       :required true}
                                      :password)
                      (label :otp "Two-Factor Code")
                      (text-field {:placeholder "leave blank if two-factor auth not enabled"}
                                  :otp)
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
