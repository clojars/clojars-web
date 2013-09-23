(ns clojars.routes.artifact
  (:require [compojure.core :refer [GET POST defroutes]]
            [clojars.db :as db]
            [clojars.auth :as auth]
            [clojars.web.jar :as view]
            [clojars.web.common :as common]
            [clojars.promote :as promote]
            [ring.util.response :as response]
            [clojure.set :as set]))

(defn show [group-id artifact-id]
  (if-let [artifact (db/find-jar group-id artifact-id)]
    (auth/try-account
     (view/show-jar account
                    artifact
                    (db/recent-versions group-id artifact-id 5)
                    (db/count-versions group-id artifact-id)))))

(defn list-versions [group-id artifact-id]
  (if-let [artifact (db/find-jar group-id artifact-id)]
    (auth/try-account
     (view/show-versions account
                         artifact
                         (db/recent-versions group-id artifact-id)))))

(defn show-version [group-id artifact-id version]
  (if-let [artifact (db/find-jar group-id artifact-id version)]
    (auth/try-account
     (view/show-jar account
                    artifact
                    (db/recent-versions group-id artifact-id 5)
                    (db/count-versions group-id artifact-id)))))

(defroutes routes
  (GET ["/:artifact-id", :artifact-id #"[^/]+"] [artifact-id]
       (show artifact-id artifact-id))
  (GET ["/:group-id/:artifact-id", :group-id #"[^/]+" :artifact-id #"[^/]+"]
       [group-id artifact-id]
       (show group-id artifact-id))

  (GET ["/:artifact-id/versions" :artifact-id #"[^/]+"] [artifact-id]
       (list-versions artifact-id artifact-id))
  (GET ["/:group-id/:artifact-id/versions"
        :group-id #"[^/]+" :artifact-id #"[^/]+"]
       [group-id artifact-id]
       (list-versions group-id artifact-id))

  (GET ["/:artifact-id/versions/:version"
        :artifact-id #"[^/]+" :version #"[^/]+"]
       [artifact-id version]
       (show-version artifact-id artifact-id version))
  (GET ["/:group-id/:artifact-id/versions/:version"
        :group-id #"[^/]+" :artifact-id #"[^/]+" :version #"[^/]+"]
       [group-id artifact-id version]
       (show-version group-id artifact-id version))

  (GET ["/:artifact-id/latest-version.svg" :artifact-id #"[^/]+"]
       [artifact-id]
       (-> (response/response (view/make-latest-version-svg artifact-id artifact-id))
           (response/content-type "image/svg+xml")))
  (GET ["/:group-id/:artifact-id/latest-version.svg"
        :group-id #"[^/]+" :artifact-id #"[^/]+"]
       [group-id artifact-id]
       (-> (response/response (view/make-latest-version-svg group-id artifact-id))
           (response/content-type "image/svg+xml")))

  (POST ["/:group-id/:artifact-id/promote/:version"
         :group-id #"[^/]+" :artifact-id #"[^/]+" :version #"[^/]+"]
        [group-id artifact-id version]
        (auth/with-account
          (auth/require-authorization
           group-id
           (if-let [jar (db/find-jar group-id artifact-id version)]
             (do (promote/promote (set/rename-keys jar {:jar_name :name
                                                        :group_name :group}))
                 (response/redirect
                  (common/jar-url {:group_name group-id
                                   :jar_name artifact-id}))))))))
