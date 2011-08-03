(ns clojars.web
  (:require [clojure.contrib.sql :as sql])
  (:use [clojars.db]
        [clojars.web dashboard group jar login search user common]
        [hiccup.core]
        [hiccup.page-helpers]
        [hiccup.form-helpers]
        [ring.middleware.session]
        [ring.middleware.session.store]
        [ring.middleware.file]
        [ring.util.response]
        [compojure.core]))

(defn not-found-doc []
  (html [:h1 "Page not found"]
        [:p "Thundering typhoons!  I think we lost it.  Sorry!"]))

(defmacro with-account [body]
  `(if-let [~'account (~'session :account)]
     (do ~body)
     (redirect "/login")))

(defmacro try-account [body]
  `(let [~'account (~'session :account)]
     (do ~body)))

(defroutes main-routes
  (GET "/search" {session :session params :params}
    (try-account
     (search account params)))
  (GET "/profile" {session :session params :params}
    (with-account
     (profile-form account)))
  (POST "/profile" {session :session params :params}
    (with-account 
      (update-profile account params)))
  (GET "/login" {params :params}
    (login-form))
  (POST "/login" {params :params}
    (login params))
  (POST "/register" {params :params}
    (register params))
  (GET "/register" {params :params}
    (register-form))
  (POST "/forgot-password" {params :params}
    (forgot-password params))
  (GET "/forgot-password" {params :params}
    (forgot-password-form))
  (GET "/logout" request
    (let [response (redirect "/")]
      (assoc-in response [:session :account] nil)))
  (GET "/" {session :session params :params}
    (try-account
     (if account
       (dashboard account)
       (index-page account))))
  (GET ["/groups/:group", :group #"[^/]+"] {session :session {group "group"} :params }
    (if-let [members (with-db (group-members group))]
      (try-account
       (show-group account group members))
      :next))
  (POST ["/groups/:group", :group #"[^/]+"] {session :session {group "group" user "user"} :params }
    (if-let [members (with-db (group-members group))]
      (try-account
       (cond
        (some #{user} members)
        (show-group account group members "They're already a member!")
        (and (some #{account} members)
             (find-user user))
        (do (add-member group user)
            (show-group account group
                        (conj members user)))
        :else
        (show-group account group members (str "No such user: "
                                               (h user)))))
      :next))
  (GET "/users/:username"  {session :session {username "username"} :params}
    (if-let [user (with-db (find-user username))]
       (try-account
        (show-user account user))
       :next))
  (GET "/:jarname" {session :session {jarname "jarname"} :params}
    (if-let [jar (with-db (find-canon-jar jarname))]
      (try-account
       (show-jar account jar))
      :next))
  (GET ["/:group/:jarname", :group #"[^/]+", :jarname #"[^/]+"]
       {session :session {group "group" jarname "jarname"} :params}
    (if-let [jar (with-db (find-jar group jarname))]
      (try-account
       (show-jar account jar))
      :next))
  (GET "/:user" {session :session {user "user"} :params}
    (if-let [user (with-db (find-user user))]
      (try-account
       (show-user account user))
      :next))
  (ANY "*" {session :session}
    (html-doc (session :account) "Page not found" (not-found-doc))))


(def clojars-app
   (-> main-routes
       wrap-session
       (wrap-file "public")
       db-middleware))

;(require 'swank.swank)
;(swank.swank/start-server "/dev/null" :port 4005)


;(use 'clojure.contrib.repl-utils)
;(show server)
;(.stop server)

;(with-db (find-jar "leiningen"))


;(def server (run-server {:port 8000} "/*" (servlet clojars-app)))
