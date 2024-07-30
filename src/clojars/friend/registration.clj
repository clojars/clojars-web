(ns clojars.friend.registration
  (:require
   [cemerick.friend.workflows :as workflow]
   [clojars.db :refer [add-user]]
   [clojars.log :as log]
   [clojars.web.user :refer [register-form new-user-validations normalize-email]]
   [ring.util.response :refer [response content-type]]
   [valip.core :refer [validate]]))

(defn register [db {:keys [email username password confirm]}]
  (let [email (normalize-email email)]
    (log/with-context {:email email
                       :username username
                       :tag :registration}
      (if-let [errors (apply validate {:email email
                                       :username username
                                       :password password}
                             (new-user-validations db confirm))]
        (do
          (log/info {:status :validation-failed})
          (->
           (response (register-form {:errors (apply concat (vals errors))
                                     :email email
                                     :username username}
                                    nil))
           (content-type "text/html")))
        (do
          (add-user db email username password)
          (log/info {:status :success})
          (workflow/make-auth {:identity username :username username}))))))

(defn workflow [db]
  (fn [{:keys [uri request-method params]}]
    (when (and (= "/register" uri)
               (= :post request-method))
      (register db params))))
