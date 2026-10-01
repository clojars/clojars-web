(ns clojars.routes.session
  (:require
   [cemerick.friend :as friend]
   [clojars.auth :as auth]
   [clojars.db :as db]
   [clojars.log :as log]
   [clojars.web.login :as view]
   [clojars.web.user :as user-web]
   [compojure.core :refer [GET POST ANY]]
   [ring.util.response :as response]))

(def ^:private resend-rate-limit-seconds 60)

(defn routes [db mailer]
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
                context  (::auth/email-verification-context session)
                user     (db/find-user db username)]
            (view/verify-email-pending-form (:email user) context flash))
          (response/redirect "/login")))

   (POST "/login/verify-email/resend" {:keys [session]}
     (if-let [username (::auth/pending-email-verification-username session)]
       (let [now-ms         (System/currentTimeMillis)
             last-resend-ms (::last-verification-resend session 0)
             elapsed-s      (long (/ (- now-ms last-resend-ms) 1000.0))]
         (if (< elapsed-s resend-rate-limit-seconds)
           (assoc (response/redirect "/login/verify-email")
                  :flash (format "Please wait %d seconds before requesting another email."
                                 (- resend-rate-limit-seconds elapsed-s)))
           (do
             (log/with-context {:tag :email-verification-resend :username username}
               (user-web/send-verification-email db mailer username)
               (log/info {:status :sent}))
             (assoc (response/redirect "/login/verify-email")
                    :flash (format "Please wait %d seconds before requesting another email."
                                   (- resend-rate-limit-seconds elapsed-s))
                    :session (assoc session ::last-verification-resend now-ms)))))
       (response/redirect "/login")))

   (friend/logout
    (ANY "/logout" _ (response/redirect "/")))))
