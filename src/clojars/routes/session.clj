(ns clojars.routes.session
  (:require [compojure.core :refer [GET ANY defroutes]]
            [ring.util.response :as response]
            [cemerick.friend :as friend]
            [clojars.web.login :as view]))

(defroutes routes
  (GET "/login" {{:keys [login_failed username]} :params :keys [flash]}
       (view/login-form login_failed username flash))
  (friend/logout
   (ANY "/logout" _ (response/redirect "/"))))