(ns clojars.web
  (:require [clojars.config :refer [config]]
            [clojars.auth :refer [with-account try-account require-authorization
                                  get-user admin?]]
            [clojars.repo :as repo]
            [clojars.friend.registration :as registration]
            [clojars.web.dashboard :refer [dashboard index-page]]
            [clojars.web.error-page :refer [wrap-exceptions]]
            [clojars.web.search :refer [search]]
            [clojars.web.browse :refer [browse]]
            [clojars.web.common :refer [html-doc]]
            [ring.middleware.file-info :refer [wrap-file-info]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.anti-forgery :refer [wrap-anti-forgery]]
            [compojure.core :refer [defroutes GET POST PUT ANY context routes]]
            [compojure.handler :refer [site]]
            [compojure.route :refer [not-found]]
            [cemerick.friend :as friend]
            [cemerick.friend.credentials :as creds]
            [cemerick.friend.workflows :as workflows]
            [clojars.routes.session :as session]
            [clojars.routes.user :as user]
            [clojars.routes.artifact :as artifact]
            [clojars.routes.group :as group]))

(defroutes main-routes
  (GET "/" _
       (try-account
        (if account
          (dashboard account)
          (index-page account))))
  (GET "/search" {:keys [params]}
       (try-account
        (search account params)))
  (GET "/projects" {:keys [params]}
       (try-account
        (browse account params)))
  session/routes
  group/routes
  artifact/routes
  ;; user routes must go after artifact routes
  ;; since they both catch /:identifier
  user/routes
  (GET "/error" _ (throw (Exception. "What!? You really want an error?")))
  (PUT "*" _ {:status 405 :headers {} :body "Did you mean to use /repo?"})
  (ANY "*" _
       (try-account
        (not-found
         (html-doc account
                   "Page not found"
                   [:h1 "Page not found"]
                   [:p "Thundering typhoons!  I think we lost it.  Sorry!"])))))

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
