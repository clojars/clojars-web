(ns clojars.web.login
  (:require [clojars.web.common :refer [html-doc]]
            [hiccup.element :refer [link-to]]
            [hiccup.form :refer [label text-field
                                 password-field submit-button]]
            [ring.util.response :refer [redirect]]
            [clojars.web.safe-hiccup :refer [form-to]]))

(defn login-form [login_failed username]
  (html-doc nil "Login"
   [:h1 "Login"]
   [:p "Don't have an account? "
    (link-to "/register" "Sign up!")]

   (when login_failed
     [:div [:p {:class :error} "Incorrect username and/or password."]
      [:p "If you have not logged in since "
       [:a {:href "https://groups.google.com/group/clojure/browse_thread/thread/5e0d48d2b82df39b"}
        "the insecure password hashes were wiped"]
       ", please use the " [:a {:href "/forgot-password"} "forgot password"]
       " functionality to reset your password."]])
   (form-to [:post "/login"]
     (label :username "Username or email:")
     (text-field :username username)
     (label :password "Password:")
     (password-field :password)
     (link-to "/forgot-password" "Forgot password?") [:br]
     (submit-button "Login"))))