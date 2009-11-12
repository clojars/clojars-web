(ns clojars.web
  (:require [clojure.contrib.sql :as sql])
  (:use compojure clojars.db))

(def *reserved-names* 
     #{"clojure" "clojars" "clojar" "register" "login"
       "pages" "logout" "password" "username" "user"
       "repo" "repos" "jar" "jars" "about" "help" "doc"
       "docs" "pages" "images" "js" "css" "maven" "api"
       "download" "create" "new" "upload" "contact" "terms"
       "group" "groups" "browse" "status" "blog" "search"
       "email" "welcome" "devel" "development" "test" "testing"
       "prod" "production" "admin" "administrator" "root"
       "webmaster" "profile" "dashboard" "settings" "options"
       "index" "files"})

(defn when-ie [& contents]
  (str
   "<!--[if IE]>"
   (apply html contents)
   "<![endif]-->"))

(defn html-doc [account title & body]
  (html 
   "<!DOCTYPE html>"
   [:html {:lang :en}
    [:head
     [:meta {:charset "utf-8"}]
     [:title
      (when title
        (str title " | "))
      "Clojars"]
     (map #(include-css (str "/stylesheets/" %))
          ["reset.css" "grid.css" "screen.css"])
     (when-ie (include-js "/js/html5.js"))]

    [:body
     [:div {:class "container_12 header"}
      [:header
       [:hgroup {:class :grid_4}
        [:h1 (link-to "/" "Clojars")]
        [:h2 "Simple Clojure jar repository"]]
       [:nav
        (if account
          (unordered-list
           [(link-to "/" "dashboard")
            (link-to "/profile" "profile")
            (link-to "/logout" "logout")])
          (unordered-list
           [(link-to "/login" "login")
            (link-to "/register" "register")]))
        [:input {:value "No search yet" :class :search}]]]
      [:div {:class :clear}]]]
    [:div {:class "container_12 article"}
     [:article
      body]]]))

(defn login-form [ & [error]]
  (html-doc nil "Login"
   [:h1 "Login"]
  
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

(defn error-list [errors]
  (when errors
     [:div {:class :error} 
      [:strong "Blistering barnacles!"]
      "  Something's not shipshape:"
      (unordered-list errors)]))

(defn register-form [ & [errors email user ssh-key]]
  (html-doc nil "Register"
   [:h1 "Register"]
   (error-list errors)
   (form-to [:post "/register"]
     (label :email "Email:")
     [:input {:type :email :name :email :id :email :value email}]
     (label :user "Username:")
     (text-field :user user)
     (label :password "Password:")
     (password-field :password)
     (label :confirm "Confirm password:")
     (password-field :confirm)
     (label :ssh-key "SSH public key:")
     (text-area :ssh-key ssh-key)
     (submit-button "Register"))))

(defn conj-when [coll test x]
  (if test
    (conj coll x)
    coll))


(defn valid-ssh-key? [key]
  (re-matches #"(ssh-\w+ \S+|\d+ \d+ \D+).*\s*" key))

(defn validate-profile 
  "Validates a profile, returning nil if it's okay, otherwise a list
  of errors."
  [account email user password confirm ssh-key]
  (-> nil
      (conj-when (blank? email) "Email can't be blank")
      (conj-when (blank? user) "Username can't be blank")
      (conj-when (blank? password) "Password can't be blank")
      (conj-when (not= password confirm) 
                 "Password and confirm password must match")
      (conj-when (or (*reserved-names* user)  ; "I told them we already
                     (and (not= account user) ; got one!" 
                          (find-user user))) 
                 "Username is already taken")
      (conj-when (not (re-matches #"[a-z0-9_-]+" user))
                 (str "Usernames must consist only of lowercase "
                      "letters, numbers, hyphens and underscores."))
      (conj-when (not (valid-ssh-key? ssh-key))
                 "Invalid SSH public key")))

(defn register [{email :email, user :user, password :password, 
                 confirm :confirm, ssh-key :ssh-key}]
  (if-let [errors (validate-profile nil email user password confirm ssh-key)]
    (register-form errors email user ssh-key)
    (do (add-user email user password ssh-key)
        [(set-session {:user user})
         (redirect-to "/welcome")])))

(defn profile-form [account & [errors]]
  (let [user (find-user account)]
    (html-doc account "Profile"
     [:h1 "Profile"]
     (error-list errors)
     (form-to [:post "/profile"]
     (label :email "Email:")
     [:input {:type :email :name :email :id :email :value (user :email)}]
     (label :password "Password:")
     (password-field :password)
     (label :confirm "Confirm password:")
     (password-field :confirm)
     (label :ssh-key "SSH public key:")
     (text-area :ssh-key (user :ssh_key))
     (submit-button "Update")))))

(defn update-profile [account {email :email, password :password, 
                               confirm :confirm, ssh-key :ssh-key}]
  (if-let [errors (validate-profile account email account password confirm ssh-key)]
    (profile-form account errors)
    (do (update-user account email account password ssh-key)
        [(redirect-to "/profile")])))

(defn not-found-doc []
  [:h1 "Page not found"]
  [:p "Thundering typhoons!  I think we lost it.  Sorry!"])

(defmacro with-account [body]
  `(if-let [~'account (~'session :account)]
     (do ~body)
     (redirect-to "/login")))

(defroutes clojars-app
  (GET "/profile"
    (with-account
     (profile-form account)))
  (POST "/profile"
    (with-account
     (update-profile account params)))
  (GET "/login"
    (login-form))
  (POST "/login"
    (login params))
  (POST "/register"
    (register params))
  (GET "/register"
    (register-form))
  (GET "/logout"
    [(session-assoc :account nil)
     (redirect-to "/")])
  (GET "/"
    (html-doc (session :account) nil [:h1 "Hello World!"]))
  (ANY "/*"
       (if-let [f (serve-file (params :*))]
         [{:headers {"Cache-Control" "max-age=3600"}} f]
         :next))
  (ANY "*"
    [404 (html-doc (session :account) "Page not found" (not-found-doc))]))

(decorate clojars-app
          (with-session)
          (with-db))

;(require 'swank.swank)
;(swank.swank/start-server "/dev/null" :port 4005)

;(run-server {:port 8000}
;            "/*" (servlet clojars-app))
