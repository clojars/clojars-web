(ns clojars.friend.registration
  (:require [cemerick.friend :as friend]
            [cemerick.friend.workflows :as workflow]
            [ring.util.response :refer [response]]
            [clojars.web.user :refer [register-form new-user-validations]]
            [clojars.db :refer [add-user]]
            [valip.core :refer [validate]]))

(defn register [db {:keys [email username password confirm pgp-key]}]
  (if-let [errors (apply validate {:email email
                                   :username username
                                   :password password
                                   :pgp-key pgp-key}
                         (new-user-validations db confirm))]
    (response (register-form (apply concat (vals errors)) email username))
    (do (add-user db email username password pgp-key)
        (workflow/make-auth {:identity username :username username}))))

(defn workflow [db]
  (fn [{:keys [uri request-method params]}]
    (when (and (= uri "/register")
               (= request-method :post))
      (register db params))))
