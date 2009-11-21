(ns clojars.web.login
  (:use clojars.web.common
        clojars.db
        compojure))

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
     (submit-button "Login"))))

(defn login [{username :user password :password}]
  (if-let [user (auth-user username password)]
    [(session-assoc :account (:user user))
     (redirect-to "/")]
    (login-form "Incorrect username or password.")))
