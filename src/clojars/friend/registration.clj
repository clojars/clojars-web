(ns clojars.friend.registration
  (:require [cemerick.friend.workflows :as workflow]
            [ring.util.response :refer [response content-type]]
            [clojars.web.user :refer [register-form new-user-validations]]
            [clojars.db :refer [add-user]]
            [valip.core :refer [validate]]))

(defn register [db {:keys [email username password confirm pgp-key]}]
  (let [pgp-key (and pgp-key (.trim pgp-key))
        email (and email (.trim email))]
    (if-let [errors (apply validate {:email email
                                     :username username
                                     :password password
                                     :pgp-key pgp-key}
                           (new-user-validations db confirm))]
      (->
        (response (register-form {:errors (apply concat (vals errors))
                                  :email email
                                  :username username
                                  :pgp-key pgp-key}))
        (content-type "text/html"))
      (do (add-user db email username password pgp-key)
          (workflow/make-auth {:identity username :username username})))))

(defn workflow [db]
  (fn [{:keys [uri request-method params]}]
    (when (and (= uri "/register")
               (= request-method :post))
      (register db params))))
