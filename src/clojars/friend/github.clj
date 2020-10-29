(ns clojars.friend.github
  (:require [ring.util.response :refer [redirect]]
            [cemerick.friend.workflows :as workflow]
            [clojars.github :as github]
            [clojars.db :as db]))

(defn authorize [req service]
  (redirect (github/authorization-url service)))

(defn callback [req service db]
  (let [code (-> req :params :code)
        token (github/access-token service code)
        email (github/get-primary-email service token)]
    (if (not (:verified email))
      (assoc (redirect "/login")
             :flash "Your primary e-mail is not verified")

      (if-let [user (db/find-user-by-user-or-email db (:email email))]

        (let [username (:user user)]
          (workflow/make-auth {:identity username :username username}))

        (assoc (redirect "/register")
               :flash "Your primary e-mail is not registered")))))

(defn workflow [service db]
  (fn [req]
    (case (:uri req)
      "/oauth/github/authorize" (authorize req service)
      "/oauth/github/callback" (callback req service db)
      nil)))
