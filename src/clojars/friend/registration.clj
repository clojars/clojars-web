(ns clojars.friend.registration
  (:require
   [cemerick.friend.workflows :as workflow]
   [clojars.db :refer [add-user]]
   [clojars.hcaptcha :as hcaptcha]
   [clojars.http-utils :as http-utils]
   [clojars.log :as log]
   [clojars.user-validations :as uv]
   [clojars.web.user :refer [register-form normalize-email]]))

(defn register
  [db hcaptcha {:keys [confirm email h-captcha-response password username]}]
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
          (workflow/make-auth {:identity username :username username}))))))

(defn workflow [db hcaptcha]
  (fn [{:keys [uri request-method params]}]
    (when (and (= "/register" uri)
               (= :post request-method))
      (register db hcaptcha params))))
