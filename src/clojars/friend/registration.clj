(ns clojars.friend.registration
  (:require
   [clojars.auth :as auth]
   [clojars.db :refer [add-user]]
   [clojars.hcaptcha :as hcaptcha]
   [clojars.http-utils :as http-utils]
   [clojars.log :as log]
   [clojars.user-validations :as uv]
   [clojars.web.user :refer [register-form normalize-email send-verification-email]]
   [ring.util.response :as response]))

(defn register
  [db hcaptcha mailer {:keys [confirm email h-captcha-response password username]} session]
  (let [email (normalize-email email)]
    (log/with-context {:email email
                       :username username
                       :tag :registration}
      (if-let [errors (uv/validate {:captcha  h-captcha-response
                                    :email    email
                                    :password password
                                    :username username}
                                   (uv/new-user-validations db hcaptcha confirm))]
        (do
          (log/info {:status :validation-failed})
          (http-utils/with-extra-csp-srcs
            hcaptcha/hcaptcha-csp
            (register-form hcaptcha
                           {:errors (apply concat (vals errors))
                            :email email
                            :username username}
                           nil)))
        (do
          (add-user db email username password)
          (log/info {:status :success})
          (send-verification-email db mailer username)
          (-> (response/redirect "/login/verify-email")
              (assoc :session (assoc session
                                     ::auth/pending-email-verification-username username
                                     ::auth/email-verification-context :registration))))))))

(defn workflow [db hcaptcha mailer]
  (fn [{:keys [uri request-method params session]}]
    (when (and (= "/register" uri)
               (= :post request-method))
      (register db hcaptcha mailer params session))))
