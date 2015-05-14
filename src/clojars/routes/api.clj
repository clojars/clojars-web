(ns clojars.routes.api
  (:require [clojure.set :refer [rename-keys]]
            [compojure.core :refer [GET ANY defroutes context]]
            [compojure.route :refer [not-found]]
            [ring.middleware.format-response :refer [wrap-restful-response]]
            [ring.util.response :refer [response]]
            [clojars.db :as db]
            [clojars.stats :as stats]
            [korma.core :refer [exec-raw]]))

(defn find-jars
  ([group-id]
   (find-jars group-id nil))
  ([group-id artifact-id]
   (exec-raw
     [(->> ["select j.jar_name, j.group_name, homepage, description, user, "
            "j.version as latest_version, r2.version as latest_release "
            "from jars j "
            ;; Find the latest version
            "join "
            "(select jar_name, group_name, max(created) as created "
            "from jars "
            "group by group_name, jar_name) l "
            "on j.jar_name = l.jar_name "
            "and j.group_name = l.group_name "
            ;; Find basic info for latest version
            "and j.created = l.created "
            ;; Find the created ts for latest release
            "left join "
            "(select jar_name, group_name, max(created) as created "
            "from jars "
            "where version not like '%-SNAPSHOT' "
            "group by group_name, jar_name) r "
            "on j.jar_name = r.jar_name "
            "and j.group_name = r.group_name "
            ;; Find version for latest release
            "left join "
            "(select jar_name, group_name, version, created from jars) as r2 "
            "on j.jar_name = r2.jar_name "
            "and j.group_name = r2.group_name "
            "and r.created = r2.created "
            "where j.group_name = ? "
            (when artifact-id "and j.jar_name = ? ")
            "order by j.group_name asc, j.jar_name asc"]
        (remove nil?)
        (apply str))
      (remove nil? [group-id artifact-id])]
     :results)))

(defn get-artifact [group-id artifact-id]
  (if-let [artifact (first (find-jars group-id artifact-id))]
    (let [stats (stats/all)]
      (-> artifact
        (assoc
          :recent_versions (db/recent-versions group-id artifact-id)
          :downloads (stats/download-count stats group-id artifact-id))
        (update-in [:recent_versions]
          (fn [versions]
            (map (fn [version]
                   (assoc version
                     :downloads (stats/download-count stats group-id artifact-id (:version version))))
              versions)))
        response))
    (not-found nil)))

(defroutes handler
  (context "/api" []
    (GET ["/groups/:group-id", :group-id #"[^/]+"] [group-id]
      (if-let [jars (seq (find-jars group-id))]
        (let [stats (stats/all)]
          (response
            (map (fn [jar]
                   (assoc jar
                     :downloads (stats/download-count stats group-id (:jar_name jar))))
              jars)))
        (not-found nil)))
    (GET ["/artifacts/:artifact-id", :artifact-id #"[^/]+"] [artifact-id]
      (get-artifact artifact-id artifact-id))
    (GET ["/artifacts/:group-id/:artifact-id", :group-id #"[^/]+", :artifact-id #"[^/]+"] [group-id artifact-id]
      (get-artifact group-id artifact-id))
    (GET "/users/:username" [username]
      (if-let [groups (seq (db/find-groupnames username))]
        (response {:groups groups})
        (not-found nil)))
    (ANY "*" _
      (not-found nil))))

(def routes
  (-> handler
      (wrap-restful-response :formats [:json :edn :yaml :transit-json])))
