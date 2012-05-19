(ns clojars.web
  (:use [clojars.db :only [group-membernames find-user add-member
                           find-jar recent-versions count-versions]]
        [clojars.web.dashboard :only [dashboard index-page]]
        [clojars.web.search :only [search]]
        [clojars.web.user :only [profile-form update-profile show-user
                                 register register-form
                                 forgot-password forgot-password-form]]
        [clojars.web.group :only [show-group]]
        [clojars.web.jar :only [show-jar show-versions]]
        [clojars.web.common :only [html-doc try-account]]
        [clojars.web.login :only [login login-form]]
        [clojars.web.stats :only [stats-routes]]
        [hiccup.core :only [html h]]
        [ring.middleware.file-info :only [wrap-file-info]]
        [ring.middleware.resource :only [wrap-resource]]
        [ring.util.response :only [redirect status response]]
        [compojure.core :only [defroutes GET POST ANY]]
        [compojure.handler :only [site]]
        [compojure.route :only [not-found]]))

(defn not-found-doc []
  (html [:h1 "Page not found"]
        [:p "Thundering typhoons!  I think we lost it.  Sorry!"]))

(defmacro with-account [body]
  `(if-let [~'account (~'session :account)]
     ~body
     (redirect "/login")))

(defroutes main-routes
  stats-routes
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
           (cond
            (some #{username} membernames)
            (show-group account groupname membernames "They're already a member!")
            (and (some #{account} membernames)
                 (find-user username))
            (do (add-member groupname username)
                (show-group account groupname
                            (conj membernames username)))
            :else
            (show-group account groupname membernames (str "No such user: "
                                                           (h username)))))
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
   (-> #'main-routes
       (wrap-resource "public")
       (wrap-file-info))))

(comment
  (require 'swank.swank)
  (swank.swank/start-repl)

  (find-jar "leiningen" "leiningen")
  (def server (run-server {:port 8000} "/*" (servlet clojars-app)))
  (.stop server))
