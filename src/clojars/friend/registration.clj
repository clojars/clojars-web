(ns clojars.friend.registration
  (:require [cemerick.friend :as friend]
            [cemerick.friend.workflows :as workflow]
            [clj-time.core :as time]
            [clojars
             [auth :as auth]
             [db :refer [add-user]]]
            [clojars.web.user :refer [new-user-validations register-form]]
            [ring.util.response :refer [response]]
            [valip.core :refer [validate]]))

(defn register [db {:keys [email username password confirm pgp-key]}]
  (if-let [errors (apply validate {:email email
                                   :username username
                                   :password password
                                   :pgp-key pgp-key}
                         (new-user-validations db confirm))]
    (response (register-form (apply concat (vals errors)) email username))
    (do (add-user db email username (auth/bcrypt password) pgp-key (time/now))
        (workflow/make-auth {:identity username :username username}))))

(defn workflow [db]
  (fn [{:keys [uri request-method params]}]
    (when (and (= uri "/register")
               (= request-method :post))
      (register db params))))
