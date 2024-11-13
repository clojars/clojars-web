(ns clojars.routes.api
  (:require
   [clojars
    [db :as db]
    [stats :as stats]
    [http-utils :refer [wrap-cors-headers]]]
   [clojure
    [set :as set]]
   [compojure
    [core :as compojure :refer [ANY context GET]]
    [route :refer [not-found]]]
   [ring.middleware.format-response :refer [wrap-restful-response]]
   [ring.util.response :refer [response]]))

(defn get-artifact [db stats group-id artifact-id]
  (if-let [artifact (db/find-jar-artifact db group-id artifact-id)]
    (-> artifact
        (assoc
         :recent_versions (db/recent-versions db group-id artifact-id)
         :downloads (stats/download-count stats group-id artifact-id))
        (update :recent_versions
                (fn [versions]
                  (map (fn [version]
                         (assoc version
                                :downloads (stats/download-count stats group-id artifact-id (:version version))))
                       versions)))
        (assoc :dependencies
               (->> (db/find-dependencies db group-id artifact-id (:latest_version artifact))
                    (map #(-> %
                              (select-keys [:dep_group_name :dep_jar_name :dep_version :dep_scope])
                              (set/rename-keys {:dep_group_name :group_name
                                                :dep_jar_name   :jar_name
                                                :dep_version    :version
                                                :dep_scope      :scope})))))
        response)
    (not-found nil)))

(defn handler [db stats]
  (compojure/routes
   (context "/api" []
            (GET ["/groups/:group-id", :group-id #"[^/]+"] [group-id]
                 (if-let [jars (seq (db/find-jars-information db group-id))]
                   (response
                    (map (fn [jar]
                           (assoc jar
                                  :downloads (stats/download-count stats group-id (:jar_name jar))))
                         jars))
                   (not-found nil)))
            (GET ["/artifacts/:artifact-id", :artifact-id #"[^/]+"] [artifact-id]
                 (get-artifact db stats artifact-id artifact-id))
            (GET ["/artifacts/:group-id/:artifact-id", :group-id #"[^/]+", :artifact-id #"[^/]+"] [group-id artifact-id]
                 (get-artifact db stats group-id artifact-id))
            (GET "/users/:username" [username]
                 (if-let [groups (seq (db/find-groupnames db username))]
                   (response {:groups groups})
                   (not-found nil)))
            (ANY "*" _
                 (not-found nil)))))

(defn routes [db stats]
  (-> (handler db stats)
      (wrap-cors-headers)
      (wrap-restful-response :formats [:json :edn :yaml :transit-json])))
