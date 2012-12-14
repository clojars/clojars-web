(ns clojars.web
  (:require [clojars.db :refer [group-membernames find-user add-member
                                find-jar recent-versions count-versions
                                find-user-by-user-or-email]]
            [clojars.config :refer [config]]
            [clojars.auth :refer [with-account try-account require-authorization
                                  get-user admin?]]
            [clojars.repo :as repo]
            [clojars.promote :as promote]
            [clojars.friend.registration :as registration]
            [clojars.web.dashboard :refer [dashboard index-page]]
            [clojars.web.error-page :refer [wrap-exceptions]]
            [clojars.web.search :refer [search]]
            [clojars.web.browse :refer [browse]]
            [clojars.web.user :refer [profile-form update-profile show-user
                                      register-form
                                      forgot-password forgot-password-form]]
            [clojars.web.group :refer [show-group]]
            [clojars.web.jar :refer [show-jar show-versions]]
            [clojars.web.common :refer [html-doc jar-url]]
            [clojars.web.login :refer [login-form]]
            [clojure.set :as set]
            [hiccup.core :refer [html h]]
            [ring.middleware.file-info :refer [wrap-file-info]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.util.response :refer [redirect status response]]
            [ring.middleware.anti-forgery :refer [wrap-anti-forgery]]
            [compojure.core :refer [defroutes GET POST PUT ANY context routes]]
            [compojure.handler :refer [site]]
            [compojure.route :refer [not-found]]
            [cemerick.friend :as friend]
            [cemerick.friend.credentials :as creds]
            [cemerick.friend.workflows :as workflows]))

(defroutes main-routes
  (GET "/search" {session :session params :params}
       (try-account
        (search account params)))
  (GET "/projects" {session :session params :params}
       (try-account
        (browse account params)))
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
  (friend/logout (ANY "/logout" request
                                        ; Work around friend#20 and ring-anti-forgery#10
                      (-> (ring.util.response/redirect "/")
                          (assoc :session (:session request)))))

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
  (POST ["/:groupname/:jarname/promote/:version"
         :jarname #"[^/]+" :groupname #"[^/]+" :version #"[^/]+"]
        {session :session
         {groupname :groupname jarname :jarname version :version} :params}
        (with-account
          (require-authorization groupname
            (if-let [jar (find-jar groupname jarname version)]
              (do (promote/promote (set/rename-keys jar {:jar_name :name
                                                         :group_name :group}))
                  (redirect (jar-url {:group_name groupname :jar_name jarname})))
              :next))))
  (GET "/:username" {session :session {username :username} :params}
       (if-let [user (find-user username)]
         (try-account
          (show-user account user))
         :next))
  (GET "/error" {} (throw (Exception. "What!? You really want an error?")))
  (PUT "*" _ {:status 405 :headers {} :body "Did you mean to use /repo?"})
  (ANY "*" {session :session}
       (not-found (html-doc (session :account)
                            "Page not found"
                            [:h1 "Page not found"]
                            [:p "Thundering typhoons!  I think we lost it.  Sorry!"]))))

(defroutes clojars-app
  (context "/repo" _
           (-> repo/routes
               (friend/authenticate
                {:credential-fn
                 (partial creds/bcrypt-credential-fn
                          get-user)
                 :workflows [(workflows/http-basic :realm "clojars")]
                 :allow-anon? false})
               (repo/wrap-file (:repo config))))
  (-> main-routes
      (friend/authenticate
       {:credential-fn
        (partial creds/bcrypt-credential-fn
                 get-user)
        :workflows [(workflows/interactive-form)
                    registration/workflow]})
      (wrap-anti-forgery)
      (wrap-exceptions)
      (site)
      (wrap-resource "public")
      (wrap-file-info)))
