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
       {:body (auth/with-account
                (view/profile-form account flash))
        :headers {"X-Frame-Options" "DENY"}
        :status 200})
  (POST "/profile" {:keys [params]}
        (auth/with-account
          (view/update-profile account params)))

  (GET "/register" _
       (view/register-form))

  (GET "/forgot-password" _
       {:body (view/forgot-password-form)
        :headers {"X-Frame-Options" "DENY"}
        :status 200})
  (POST "/forgot-password" {:keys [params]}
        (view/forgot-password params))

  (GET "/users/:username" [username]
       (show username))
  (GET "/:username" [username]
       (show username)))
