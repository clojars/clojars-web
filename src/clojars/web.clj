(ns clojars.web
  (:use [clojars.db :only [with-db group-members find-user add-member
                           find-jar find-canon-jar db-middleware]]
        [clojars.web.dashboard :only [dashboard index-page]]
        [clojars.web.search :only [search]]
        [clojars.web.user :only [profile-form update-profile show-user
                                 register register-form]]
        [clojars.web.group :only [show-group]]
        [clojars.web.jar :only [show-jar]]
        [clojars.web.common :only [html-doc]]
        [clojars.web.login :only [login login-form]]
        [hiccup.core :only [html h]]
        [ring.middleware.session :only [wrap-session]]
        [ring.middleware.file :only [wrap-file]]
        [ring.util.response :only [redirect]]
        [compojure.core :only [defroutes GET POST ANY]]))

(defn not-found-doc []
  (html [:h1 "Page not found"]
        [:p "Thundering typhoons!  I think we lost it.  Sorry!"]))

(defmacro with-account [body]
  `(if-let [~'account (~'session :account)]
     ~body
     (redirect "/login")))

(defmacro try-account [body]
  `(let [~'account (~'session :account)]
     ~body))

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
  (GET ["/:jarname", :jarname #"[^/]+"] {session :session {jarname "jarname"} :params}
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

(comment
  (require 'swank.swank)
  (swank.swank/start-repl)

  (with-db (find-jar "leiningen"))
  (def server (run-server {:port 8000} "/*" (servlet clojars-app)))
  (.stop server))
