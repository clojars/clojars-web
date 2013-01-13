(ns clojars.routes.user
  (:require [compojure.core :refer [GET POST defroutes]]
            [clojars.auth :as auth]
            [clojars.web.user :as view]
            [clojars.db :as db]))

(defn show [username]
  (if-let [user (db/find-user username)]
    (auth/try-account
     (view/show-user account user))))

(defroutes routes
  (GET "/profile" _
       (auth/with-account
         (view/profile-form account)))
  (POST "/profile" {:keys [params]}
        (auth/with-account
          (view/update-profile account params)))

  (GET "/register" _
       (view/register-form))

  (GET "/forgot-password" _
       (view/forgot-password-form))
  (POST "/forgot-password" {:keys [params]}
        (view/forgot-password params))

  (GET "/users/:username" [username]
       (show username))
  (GET "/:username" [username]
       (show username)))