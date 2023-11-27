(ns clojars.routes.user
  (:require
   [cemerick.friend.credentials :as creds]
   [clojars.auth :as auth]
   [clojars.db :as db]
   [clojars.event :as event]
   [clojars.hcaptcha :as hcaptcha]
   [clojars.http-utils :as http-utils]
   [clojars.log :as log]
   [clojars.routes.common :as common]
   [clojars.web.user :as view]
   [compojure.core :as compojure :refer [DELETE GET POST PUT]]
   [ring.util.response :refer [redirect]]))

(defn show [db username]
  (when-let [user (db/find-user db username)]
    (auth/try-account
     #(view/show-user db % user))))

(defn- with-data-img-src
  "Allows data: badges to be shown on the mfa page to allow the qrcode to load."
  [body]
  (http-utils/with-extra-csp-srcs {:img-src ["data:"]} body))

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
        (with-data-img-src
          (view/setup-mfa account (db/find-user db account) nil)))

      :else
      (do
        (log/info {:status :failed
                   :reason :password-incorrect})
        (assoc (redirect "/mfa")
               :flash "Password incorrect.")))))

(defn- confirm-mfa [db
                    event-emitter
                    account
                    {:as user :keys [otp_active]}
                    {:keys [otp]}
                    details]
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
        (event/emit event-emitter :mfa-activated
                    (merge {:username account} details))
        (view/mfa account user (view/recovery-code-message recovery-code)))

      :else
      (do
        (log/info {:status :failed
                   :reason :otp-token-incorrect})
        (with-data-img-src
          (view/setup-mfa account user "Two-factor token incorrect."))))))

(defn- disable-mfa [db
                    event-emitter
                    account
                    {:as user :keys [otp_active]}
                    {:keys [password]}
                    details]
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
        (event/emit event-emitter
                    :mfa-deactivated
                    (merge {:username account
                            :source :user}
                           details))
        (assoc (redirect "/mfa")
               :flash "Two-factor auth disabled."))

      :else
      (do
        (log/info {:status :failed
                   :reason :password-incorrect})
        (assoc (redirect "/mfa")
               :flash "Password incorrect.")))))

(defn routes [db event-emitter hcaptcha mailer]
  (compojure/routes
   (GET "/profile" {:keys [flash]}
        (auth/with-account
          #(view/profile-form % (db/find-user db %) flash)))
   (POST "/profile" {:as request :keys [params]}
         (auth/with-account
           #(view/update-profile db event-emitter % params (common/request-details request))))

   (GET "/mfa" {:keys [flash]}
        (auth/with-account
          #(view/mfa % (db/find-user db %) flash)))
   (POST "/mfa" {:keys [params]}
         (auth/with-account
           #(create-mfa db % (db/find-user db %) params)))
   (PUT "/mfa" {:as request :keys [params]}
        (auth/with-account
          #(confirm-mfa db event-emitter %
                        (db/find-user db %) params (common/request-details request))))
   (DELETE "/mfa" {:as request :keys [params]}
           (auth/with-account
             #(disable-mfa db event-emitter % (db/find-user db %) params (common/request-details request))))

   (GET "/notification-preferences" {:keys [flash]}
        (auth/with-account
          #(view/notifications-form % (db/find-user db %) flash)))
   (POST "/notification-preferences" {:keys [params]}
         (auth/with-account
           #(view/update-notifications db % params)))

   (GET "/register" {:keys [params flash]}
        (http-utils/with-extra-csp-srcs hcaptcha/hcaptcha-csp
          (view/register-form hcaptcha params flash)))

   (GET "/forgot-password" _
        (view/forgot-password-form))
   (POST "/forgot-password" {:as request :keys [params]}
         (view/forgot-password db mailer params (common/request-details request)))

   (GET "/password-resets/:reset-code" [reset-code]
        (view/reset-password-form db reset-code))

   (POST "/password-resets/:reset-code" {:as request {:keys [reset-code password confirm]} :params}
         (view/reset-password db event-emitter reset-code {:password password :confirm confirm} (common/request-details request)))

   (GET "/users/:username" [username]
        (show db username))
   (GET "/:username" [username]
        (show db username))))
