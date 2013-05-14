(ns clojars.routes.session
  (:require [compojure.core :refer [GET ANY defroutes]]
            [ring.util.response :as response]
            [cemerick.friend :as friend]
            [clojars.web.login :as view]))

(defroutes routes
  (GET "/login" [login_failed username]
       (view/login-form login_failed username))
  (friend/logout
   (ANY "/logout" _ (response/redirect "/"))))