(ns clojars.web.login
  (:require [clojars.web.common :refer [html-doc]]
            [hiccup.element :refer [link-to]]
            [hiccup.form :refer [label text-field
                                 password-field submit-button]]
            [ring.util.response :refer [redirect]]
            [clojars.web.safe-hiccup :refer [form-to]]))

(defn login-form [login_failed username]
  (html-doc "Login" {}
   [:div.small-section
    [:h1 "Login"]
    [:p.hint "Don't have an account? "
     (link-to "/register" "Sign up!")]

    (when login_failed
      [:div [:p.error "Incorrect username and/or password."]
       [:p.hint "If you have not logged in since "
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
             (link-to {:class :hint-link} "/forgot-password" "Forgot your username or password?")
             (submit-button "Login"))]))
