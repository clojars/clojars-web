(ns clojars.routes.user
  (:require
   [cemerick.friend.credentials :as creds]
   [clojars.auth :as auth]
   [clojars.db :as db]
   [clojars.event :as event]
   [clojars.web.user :as view]
   [compojure.core :as compojure :refer [DELETE GET POST PUT]]
   [ring.util.response :refer [redirect]]
   [clojars.log :as log]))

(defn show [db username]
  (if-let [user (db/find-user db username)]
    (auth/try-account
     #(view/show-user db % user))))

(defn- create-mfa [db
                   account
                   {:as user :keys [otp_active]}
                   {:keys [password]}]
  (log/with-context {:tag :mfa-creation
                     :username account}
    (cond
      otp_active
      (do
        (log/info {:status :failed
                   :reason :mfa-already-activated})
        (assoc (redirect "/mfa")
               :flash "Two-factor auth already enabled."))

      (creds/bcrypt-verify password (:password user))
      (do
        (db/set-otp-secret-key! db account)
        (log/info {:status :success})
        (view/setup-mfa account (db/find-user db account) nil))

      :else
      (do
        (log/info {:status :failed
                   :reason :password-incorrect})
        (assoc (redirect "/mfa")
               :flash "Password incorrect.")))))

(defn- confirm-mfa [db
                    account
                    {:as user :keys [otp_active]}
                    {:keys [otp]}]
  (log/with-context {:tag :mfa-confirmation
                     :username account}
    (cond
      otp_active
      (do
        (log/info {:status :failed
                   :reason :mfa-already-activated})
        (assoc (redirect "/mfa")
               :flash "Two-factor auth already enabled."))

      (auth/valid-totp-token? otp user)
      (let [recovery-code (db/enable-otp! db account)
            user (db/find-user db account)]
        (log/info {:status :success})
        (event/emit :mfa-activated {:username account})
        (view/mfa account user (view/recovery-code-message recovery-code)))

      :else
      (do
        (log/info {:status :failed
                   :reason :otp-token-incorrect})
        (view/setup-mfa account user "Two-factor token incorrect.")))))

(defn- disable-mfa [db
                    account
                    {:as user :keys [otp_active]}
                    {:keys [password]}]
  (log/with-context {:tag :mfa-disable
                     :username account}
    (cond
      (not otp_active)
      (do
        (log/info {:status :failed
                   :reason :mfa-already-disabled})
        (assoc (redirect "/mfa")
               :flash "Two-factor auth already disabled."))

      (creds/bcrypt-verify password (:password user))
      (do
        (db/disable-otp! db account)
        (log/info {:status :success})
        (event/emit :mfa-deactivated {:username account
                                      :source :user})
        (assoc (redirect "/mfa")
               :flash "Two-factor auth disabled."))

      :else
      (do
        (log/info {:status :failed
                   :reason :password-incorrect})
        (assoc (redirect "/mfa")
               :flash "Password incorrect.")))))

(defn routes [db mailer]
  (compojure/routes
   (GET "/profile" {:keys [flash]}
        (auth/with-account
          #(view/profile-form % (db/find-user db %) flash)))
   (POST "/profile" {:keys [params]}
         (auth/with-account
           #(view/update-profile db % params)))

   (GET "/mfa" {:keys [flash]}
        (auth/with-account
          #(view/mfa % (db/find-user db %) flash)))
   (POST "/mfa" {:keys [params]}
         (auth/with-account
           #(create-mfa db % (db/find-user db %) params)))
   (PUT "/mfa" {:keys [params]}
         (auth/with-account
           #(confirm-mfa db % (db/find-user db %) params)))
   (DELETE "/mfa" {:keys [params]}
         (auth/with-account
           #(disable-mfa db % (db/find-user db %) params)))

   (GET "/register" {:keys [params flash]}
        (view/register-form params flash))

   (GET "/forgot-password" _
        (view/forgot-password-form))
   (POST "/forgot-password" {:keys [params]}
         (view/forgot-password db mailer params))

   (GET "/password-resets/:reset-code" [reset-code]
        (view/reset-password-form db reset-code))

   (POST "/password-resets/:reset-code" {{:keys [reset-code password confirm]} :params}
         (view/reset-password db reset-code {:password password :confirm confirm}))

   (GET "/users/:username" [username]
        (show db username))
   (GET "/:username" [username]
        (show db username))))
