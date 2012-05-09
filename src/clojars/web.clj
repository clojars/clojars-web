(ns clojars.web
  (:require [clojars.db :refer [group-membernames find-user add-member
                                find-jar recent-versions count-versions
                                find-user-by-user-or-email]]
            [clojars.config :refer [config]]
            [clojars.auth :refer [with-account try-account require-authorization
                                  authorized?]]
            [clojars.repo :as repo]
            [clojars.friend.registration :as registration]
            [clojars.web.dashboard :refer [dashboard index-page]]
            [clojars.web.search :refer [search]]
            [clojars.web.user :refer [profile-form update-profile show-user
                                      register-form
                                      forgot-password forgot-password-form]]
            [clojars.web.group :refer [show-group]]
            [clojars.web.jar :refer [show-jar show-versions]]
            [clojars.web.common :refer [html-doc]]
            [clojars.web.login :refer [login-form]]
            [hiccup.core :refer [html h]]
            [ring.middleware.file-info :refer [wrap-file-info]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.util.response :refer [redirect status response]]
            [compojure.core :refer [defroutes GET POST PUT ANY context]]
            [compojure.handler :refer [site]]
            [compojure.route :refer [not-found]]
            [cemerick.friend :as friend]
            [cemerick.friend.credentials :as creds]
            [cemerick.friend.workflows :as workflows]
            [clojure.java.io :as io]
            [clojure.string :as string]))

(defn not-found-doc []
  (html [:h1 "Page not found"]
        [:p "Thundering typhoons!  I think we lost it.  Sorry!"]))

(defroutes main-routes
  (context "/repo" request repo/routes)
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
       (login-form params))
  (GET "/register" {params :params}
       (register-form))
  (POST "/forgot-password" {params :params}
        (forgot-password params))
  (GET "/forgot-password" {params :params}
       (forgot-password-form))
  (friend/logout (ANY "/logout" request (ring.util.response/redirect "/")))

  (GET "/" {session :session params :params}
       (try-account
        (if account
          (dashboard account)
          (index-page account))))
  (GET ["/groups/:groupname", :groupname #"[^/]+"]
       {session :session {groupname :groupname} :params }
       (if-let [membernames (group-membernames groupname)]
         (try-account
          (show-group account groupname membernames))
         :next))
  (POST ["/groups/:groupname", :groupname #"[^/]+"]
        {session :session {groupname :groupname username :user} :params }
        (if-let [membernames (group-membernames groupname)]
          (try-account
           (require-authorization
            groupname
            (cond
             (some #{username} membernames)
             (show-group account groupname membernames "They're already a member!")
             (find-user username)
             (do (add-member groupname username)
                 (show-group account groupname
                             (conj membernames username)))
             :else
             (show-group account groupname membernames (str "No such user: "
                                                            (h username))))))
          :next))
  (GET "/users/:username"  {session :session {username :username} :params}
       (if-let [user (find-user username)]
         (try-account
          (show-user account user))
         :next))
  (GET ["/:jarname", :jarname #"[^/]+"]
       {session :session {jarname :jarname} :params}
       (if-let [jar (find-jar jarname jarname)]
         (try-account
          (show-jar account
                    jar
                    (recent-versions jarname jarname 5)
                    (count-versions jarname jarname)))
         :next))
  (GET ["/:jarname/versions"
        :jarname #"[^/]+" :group #"[^/]+"]
       {session :session {jarname :jarname} :params}
       (if-let [jar (find-jar jarname jarname)]
         (try-account
          (show-versions account jar (recent-versions jarname jarname)))
         :next))
  (GET ["/:jarname/versions/:version"
        :jarname #"[^/]+" :version #"[^/]+"]
       {session :session
        {version :version jarname :jarname} :params}
       (if-let [jar (find-jar jarname jarname version)]
         (try-account
          (show-jar account
                    jar
                    (recent-versions jarname jarname 5)
                    (count-versions jarname jarname)))
         :next))
  (GET ["/:groupname/:jarname", :jarname #"[^/]+" :groupname #"[^/]+"]
       {session :session {groupname :groupname jarname :jarname} :params}
       (if-let [jar (find-jar groupname jarname)]
         (try-account
          (show-jar account
                    jar
                    (recent-versions groupname jarname 5)
                    (count-versions groupname jarname)))
         :next))
  (GET ["/:groupname/:jarname/versions"
        :jarname #"[^/]+" :groupname #"[^/]+"]
       {session :session {groupname :groupname jarname :jarname} :params}
       (if-let [jar  (find-jar groupname jarname)]
         (try-account
          (show-versions account jar (recent-versions groupname jarname)))
         :next))
  (GET ["/:groupname/:jarname/versions/:version"
        :jarname #"[^/]+" :groupname #"[^/]+" :version #"[^/]+"]
       {session :session
        {version :version groupname :groupname jarname :jarname} :params}
       (if-let [jar (find-jar groupname jarname version)]
         (try-account
          (show-jar account
                    jar
                    (recent-versions groupname jarname 5)
                    (count-versions groupname jarname)))
         :next))
  (GET "/:username" {session :session {username :username} :params}
       (if-let [user (find-user username)]
         (try-account
          (show-user account user))
         :next))
  (ANY "*" {session :session}
       (not-found (html-doc (session :account)
                            "Page not found"
                            (not-found-doc)))))

(def clojars-app
  (site
   (-> main-routes
       (friend/authenticate
        {:credential-fn
         (partial creds/bcrypt-credential-fn
                  (fn [id]
                    (when-let [{:keys [user password]}
                               (find-user-by-user-or-email id)]
                      {:username user :password password})))
         :workflows [(workflows/interactive-form)
                     registration/workflow
                     (workflows/http-basic)]
         :login-uri "/login"
         :default-landing-uri "/"})
       (repo/wrap-file-at (:repo config) "/repo")
       (wrap-resource "public")
       (wrap-file-info))))
