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
  (GET "/profile" {:keys [flash]}
       (auth/with-account
         (view/profile-form account flash)))
  (POST "/profile" {:keys [params]}
        (auth/with-account
          (view/update-profile account params)))

  (GET "/register" _
       (view/register-form))

  (GET "/forgot-password" _
       (view/forgot-password-form))
  (POST "/forgot-password" {:keys [params]}
        (view/forgot-password params))

  (GET "/password-resets/:reset-code" [reset-code]
       (view/edit-password-form reset-code))

  (POST "/password-resets/:reset-code" {{:keys [reset-code password confirm]} :params}
        (view/edit-password reset-code {:password password :confirm confirm}))

  (GET "/users/:username" [username]
       (show username))
  (GET "/:username" [username]
       (show username)))
