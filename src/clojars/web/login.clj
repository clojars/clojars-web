(ns clojars.web.login
  (:require [clojars.web.common :refer [html-doc]]
            [clojars.db :refer [auth-user update-user]]
            [hiccup.page-helpers :refer [link-to]]
            [hiccup.form-helpers :refer [form-to label text-field
                                         password-field submit-button]]
            [ring.util.response :refer [redirect]]))

(defn login-form [ & [error]]
  (html-doc nil "Login"
   [:h1 "Login"]
   [:p "Don't have an account? "
    (link-to "/register" "Sign up!")]

   (when error
     [:div {:class :error} (str error)])
   (form-to [:post "/login"]
     (label :username "Username or email:")
     (text-field :username)
     (label :password "Password:")
     (password-field :password)
     (link-to "/forgot-password" "Forgot password?") [:br]
     (submit-button "Login"))))

(defn login [{username :username password :password}]
  (if-let [user (auth-user username password)]
    (let [response (redirect "/")]
      ;; presence of salt indicates sha1'd password, so re-hash to bcrypt
      (when (not (empty? (:salt user "")))
        (update-user (:user user) (:email user) (:user user)
                     password (:ssh_key user)))
      (assoc-in response [:session :account] (:user user)))
    (login-form "Incorrect username or password.")))
