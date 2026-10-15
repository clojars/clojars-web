(ns clojars.routes.session
  (:require
   [cemerick.friend :as friend]
   [clojars.auth :as auth]
   [clojars.event :as event]
   [clojars.models.user :as user-m]
   [clojars.web.login :as view]
   [compojure.core :refer [GET POST ANY]]
   [ring.util.response :as response]))

(defn routes [db event-emitter]
  (compojure.core/routes
   (GET "/login" {:keys [flash params]}
        (let [{:keys [login_failed username]} params]
          (view/login-form login_failed username flash)))

   (GET "/login/mfa" {:keys [flash params session]}
        (if (auth/pending-mfa? session)
          (view/mfa-form (:otp_failed params) flash)
          (response/redirect "/login")))

   (GET "/login/verify-email" {:keys [flash session]}
        (if (auth/pending-email-verification? session)
          (let [username (::auth/pending-email-verification-username session)
                user     (user-m/find-user db username)]
            (view/verify-email-pending-form (:email user) flash))
          (response/redirect "/login")))

   (POST "/login/verify-email/resend" {:keys [session]}
         (if-let [username (::auth/pending-email-verification-username session)]
           (let [user (user-m/find-user db username)
                 rate-limit-elapsed? (user-m/verify-email-send-allowed? user)]
             (when rate-limit-elapsed?
               (event/emit event-emitter :email-verification-email-requested {:username username}))
             (assoc (response/redirect "/login/verify-email")
                    :flash (format "%sPlease wait %d seconds before requesting another email."
                                   (if rate-limit-elapsed?
                                     "Email resent. "
                                     "")
                                   user-m/verify-email-resend-rate-limit-seconds)))
           (response/redirect "/login")))

   (friend/logout
    (ANY "/logout" _ (response/redirect "/")))))
