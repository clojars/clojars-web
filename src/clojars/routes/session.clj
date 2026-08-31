(ns clojars.routes.session
  (:require
   [cemerick.friend :as friend]
   [clojars.auth :as auth]
   [clojars.hcaptcha :as hcaptcha]
   [clojars.http-utils :as http-utils]
   [clojars.registration :as registration]
   [clojars.web.login :as view]
   [clojars.web.user :as user-view]
   [compojure.core :as compojure :refer [ANY GET POST]]
   [ring.util.response :as response]))

(defn routes
  [db event-emitter hcaptcha]
  (compojure/routes
   (GET "/login" {:keys [flash params]}
        (let [{:keys [login_failed username]} params]
          (view/login-form login_failed username flash)))
   (POST "/login" request
         (http-utils/redirect-on-auth request (auth/verify-password db request)))
   (GET "/login/mfa" {:keys [flash params session]}
        (if (auth/pending-mfa? session)
          (view/mfa-form (:otp_failed params) flash)
          (response/redirect "/login")))
   (POST "/login/mfa" request
         (http-utils/redirect-on-auth request (auth/verify-mfa db event-emitter request)))

   (friend/logout
    (ANY "/logout" _ (response/redirect "/")))

   (GET "/register" {:keys [params flash]}
        (http-utils/with-extra-csp-srcs hcaptcha/hcaptcha-csp
          (user-view/register-form hcaptcha params flash)))
   (POST "/register" {:as request :keys [params]}
         (http-utils/redirect-on-auth request (registration/register db hcaptcha params)))))
