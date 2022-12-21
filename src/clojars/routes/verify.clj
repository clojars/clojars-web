(ns clojars.routes.verify
  (:require
   [clojars.auth :as auth]
   [clojars.log :as log]
   [clojars.verification :as verification]
   [clojars.web.group-verification :as view]
   [ring.util.response :refer [redirect]]
   [compojure.core :as compojure :refer [GET POST]]))

(defn verify-via-TXT
  [db username params]
  (let [request (merge {:username username}
                       (select-keys params [:domain :group]))]
    (log/with-context (assoc request :tag :verify-group-via-TXT)
      (let [result (verification/verify-group-by-TXT db request)]
        (if-some [error (:error result)]
          (log/info {:status :failed
                     :error  error})
          (do
            (log/info {:status :succeeded})
            (log/audit db {:tag     :group-verified
                           :message (format "group '%s' verified" (:group request))})))
        (-> (redirect "/verify/group")
            (assoc :flash (merge request result)))))))

(defn routes [db]
  (compojure/routes
   (POST "/verify/group/txt" {:keys [params]}
         (auth/with-account
           #(verify-via-TXT db % params)))
   (GET "/verify/group" {:keys [flash]}
        (auth/with-account
          #(view/index % flash)))))
