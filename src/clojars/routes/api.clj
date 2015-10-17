(ns clojars.routes.api
  (:require [clojars
             [db :as db]
             [stats :as stats]]
            [compojure
             [core :as compojure :refer [ANY context GET]]
             [route :refer [not-found]]]
            [ring.middleware.format-response :refer [wrap-restful-response]]
            [ring.util.response :refer [response]]))

(defn get-artifact [db group-id artifact-id]
  (if-let [artifact (first (db/find-jars-information db group-id artifact-id))]
    (let [stats (stats/all)]
      (-> artifact
        (assoc
          :recent_versions (db/recent-versions db group-id artifact-id)
          :downloads (stats/download-count stats group-id artifact-id))
        (update-in [:recent_versions]
          (fn [versions]
            (map (fn [version]
                   (assoc version
                     :downloads (stats/download-count stats group-id artifact-id (:version version))))
              versions)))
        response))
    (not-found nil)))

(defn handler [db]
  (compojure/routes
   (context "/api" []
            (GET ["/groups/:group-id", :group-id #"[^/]+"] [group-id]
                 (if-let [jars (seq (db/find-jars-information db group-id))]
                   (let [stats (stats/all)]
                     (response
                      (map (fn [jar]
                             (assoc jar
                                    :downloads (stats/download-count stats group-id (:jar_name jar))))
                           jars)))
                   (not-found nil)))
            (GET ["/artifacts/:artifact-id", :artifact-id #"[^/]+"] [artifact-id]
                 (get-artifact db artifact-id artifact-id))
            (GET ["/artifacts/:group-id/:artifact-id", :group-id #"[^/]+", :artifact-id #"[^/]+"] [group-id artifact-id]
                 (get-artifact db group-id artifact-id))
            (GET "/users/:username" [username]
                 (if-let [groups (seq (db/find-groupnames db username))]
                   (response {:groups groups})
                   (not-found nil)))
            (ANY "*" _
                 (not-found nil)))))

(defn routes [db]
  (-> (handler db)
      (wrap-restful-response :formats [:json :edn :yaml :transit-json])))
