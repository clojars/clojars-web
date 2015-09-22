(ns clojars.friend.registration
  (:require [cemerick.friend :as friend]
            [cemerick.friend.workflows :as workflow]
            [ring.util.response :refer [response]]
            [clojars.web.user :refer [register-form new-user-validations]]
            [clojars.db :refer [add-user]]
            [valip.core :refer [validate]]))

(defn register [{:keys [email username password confirm ssh-key pgp-key]}]
  (if-let [errors (apply validate {:email email
                                   :username username
                                   :password password
                                   :ssh-key ssh-key
                                   :pgp-key pgp-key}
                         (new-user-validations confirm))]
    (response (register-form (apply concat (vals errors)) email username ssh-key))
    (do (add-user email username password ssh-key pgp-key)
        (workflow/make-auth {:identity username :username username}))))

(defn workflow [{:keys [uri request-method params]}]
  (when (and (= uri "/register")
             (= request-method :post))
    (register params)))
