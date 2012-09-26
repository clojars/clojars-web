(ns clojars.friend.registration
  (:require [cemerick.friend :as friend]
            [cemerick.friend.workflows :as workflow]
            [ring.util.response :refer [response]]
            [clojars.web.user :refer [register-form validate-profile]]
            [clojars.db :refer [add-user]]))

(defn register [{:keys [email username password confirm ssh-key pgp-key]}]
  (if-let [errors (validate-profile nil email username password confirm
                                    ssh-key pgp-key)]
    (response (register-form errors email username ssh-key pgp-key))
    (do (add-user email username password ssh-key pgp-key)
        (workflow/make-auth {:identity username :username username}))))

(defn workflow [{:keys [uri request-method params]}]
  (when (and (= uri "/register")
             (= request-method :post))
    (register params)))