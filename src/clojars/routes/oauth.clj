(ns clojars.routes.oauth
  (:require
   [clojars.http-utils :as http-utils]
   [clojars.oauth :as oauth]
   [compojure.core :as compojure :refer [GET]]))

(defn github-routes
  [db github-oauth-service http-service]
  (compojure/routes
   (GET "/oauth/github/authorize" _
        (oauth/authorize github-oauth-service))
   (GET "/oauth/github/callback" request
        (http-utils/redirect-on-auth
         request
         (oauth/callback request github-oauth-service http-service db)))))

(defn gitlab-routes
  [db gitlab-oauth-service http-service]
  (compojure/routes
   (GET "/oauth/gitlab/authorize" _
        (oauth/authorize gitlab-oauth-service))
   (GET "/oauth/gitlab/callback" request
        (http-utils/redirect-on-auth
         request
         (oauth/callback request gitlab-oauth-service http-service db)))))
