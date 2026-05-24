(ns clojars.routes.session
  (:require
   [cemerick.friend :as friend]
   [clojars.auth :as auth]
   [clojars.web.login :as view]
   [compojure.core :refer [GET ANY defroutes]]
   [ring.util.response :as response]))

(defroutes routes
  (GET "/login" {:keys [flash params]}
       (let [{:keys [login_failed username]} params]
         (view/login-form login_failed username flash)))
  (GET "/login/mfa" {:keys [flash params session]}
       (if (auth/pending-mfa? session)
         (view/mfa-form (:otp_failed params) flash)
         (response/redirect "/login")))
  (friend/logout
   (ANY "/logout" _ (response/redirect "/"))))
