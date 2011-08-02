(ns clojars.web.login
  (:use clojars.web.common
        clojars.db
        hiccup.core
        hiccup.page-helpers
        hiccup.form-helpers
        ring.middleware.session.store
        ring.util.response))

(defn login-form [ & [error]]
  (html-doc nil "Login"
   [:h1 "Login"]
   [:p "Don't have an account? "
    (link-to "/register" "Sign up!")]

   (when error
     [:div {:class :error} (str error)])
   (form-to [:post "/login"]
     (label :user "Username or email:")
     (text-field :user)
     (label :password "Password:")
     (password-field :password)
     (link-to "/forgot-password" "Forgot password?") [:br]
     (submit-button "Login"))))

(defn login [{username "user" password "password"}]
  (if-let [user (auth-user username password)]
    (let [response (redirect "/")]
      (assoc-in response [:session :account] (:user user)))
    (login-form "Incorrect username or password.")))
