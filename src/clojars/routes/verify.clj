(ns clojars.routes.verify
  (:require
   [clojars.auth :as auth]
   [clojars.event :as event]
   [clojars.log :as log]
   [clojars.verification :as verification]
   [clojars.web.group-verification :as view]
   [compojure.core :as compojure :refer [GET POST]]
   [ring.util.response :refer [redirect]]))

(defn- handle-request
  [db event-emitter tag f request]
  (log/with-context (assoc request :tag tag)
    (let [result (f db request)
          request+result (merge request result)]
      (event/emit event-emitter :group-verification-request request+result)
      (if-some [error (:error result)]
        (log/info {:status :failed
                   :error  error})
        (do
          (log/info {:status :succeeded})
          (log/audit db {:tag     tag
                         :message (:message result)})))
      (-> (redirect "/verify/group")
          (assoc :flash request+result)))))

(defn verify-via-parent
  "Verifies a group via ownership of a parent group."
  [db event-emitter username params]
  (handle-request db event-emitter :verify-group-via-parent-group
                  verification/verify-group-by-parent-group
                  (merge {:username username}
                         (select-keys params [:group]))))

(defn verify-via-TXT
  "Verifies a group via a DNS TXT record."
  [db event-emitter username params]
  (handle-request db event-emitter :verify-group-via-TXT
                  verification/verify-group-by-TXT
                  (merge {:username username}
                         (select-keys params [:domain :group]))))

(defn verify-via-vcs
  "Verifies a group via the existence of a GitHub or Gitlab repository to prove
  organization ownership."
  [db event-emitter username params]
  (handle-request db event-emitter :verify-group-via-vcs
                  verification/verify-vcs-groups
                  (merge {:username username}
                         (select-keys params [:url]))))

(defn routes [db event-emitter]
  (compojure/routes
   (POST "/verify/group/parent" {:keys [params]}
         (auth/with-account
           #(verify-via-parent db event-emitter % params)))
   (POST "/verify/group/txt" {:keys [params]}
         (auth/with-account
           #(verify-via-TXT db event-emitter % params)))
   (POST "/verify/group/vcs" {:keys [params]}
         (auth/with-account
           #(verify-via-vcs db event-emitter % params)))
   (GET "/verify/group" {:keys [flash]}
        (auth/with-account
          #(view/index % flash)))))
