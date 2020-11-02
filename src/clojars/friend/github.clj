(ns clojars.friend.github
  (:require [ring.util.response :refer [redirect]]
            [cemerick.friend.workflows :as workflow]
            [clojars.github :as github]
            [clojars.db :as db]))

(defn authorize [req service]
  (redirect (github/authorization-url service)))

(defn callback [req service db]
  (if-let [error (-> req :params :error)]
    (if (= error "access_denied")
      (assoc (redirect "/login")
             :flash "You declined access to your account")
      (throw (Exception. (str (-> req :params :error_description)))))

    (let [code (-> req :params :code)
          token (github/access-token service code)
          emails (github/get-verified-emails service token)]
      (if (empty? emails)
        (assoc (redirect "/login")
               :flash "No verified e-mail was found")

        (if-let [user (db/find-user-by-email-in db emails)]

          (let [username (:user user)]
            (workflow/make-auth {:identity username :username username}))

          (assoc (redirect "/register")
                 :flash "None of your e-mails are registered"))))))

(defn workflow [service db]
  (fn [req]
    (case (:uri req)
      "/oauth/github/authorize" (authorize req service)
      "/oauth/github/callback" (callback req service db)
      nil)))
