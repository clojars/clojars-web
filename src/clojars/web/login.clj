(ns clojars.web.login
  (:require [clojars.web.common :refer [html-doc]]
            [clojars.db :refer [auth-user]]
            [hiccup.page-helpers :refer [link-to]]
            [hiccup.form-helpers :refer [form-to label text-field
                                         password-field submit-button]]
            [ring.util.response :refer [redirect]]))

(defn login-form [{:keys [login_failed username]}]
  (html-doc nil "Login"
   [:h1 "Login"]
   [:p "Don't have an account? "
    (link-to "/register" "Sign up!")]

   (when login_failed
     [:div {:class :error} "Incorrect username or password."])
   (form-to [:post "/login"]
     (label :username "Username or email:")
     (text-field :username username)
     (label :password "Password:")
     (password-field :password)
     (link-to "/forgot-password" "Forgot password?") [:br]
     (submit-button "Login"))))