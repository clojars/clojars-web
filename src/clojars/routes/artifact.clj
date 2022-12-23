(ns clojars.routes.artifact
  (:require
   [clojars.auth :as auth]
   [clojars.db :as db]
   [clojars.http-utils :as http-utils]
   [clojars.web.jar :as view]
   [compojure.core :as compojure :refer [GET]]
   [ring.util.response :as response]))

(defn- with-shields-io-img-src
  "Allows shields.io badges to be shown on artifact pages."
  [body]
  (http-utils/with-extra-img-src ["https://img.shields.io"] body))

(defn show [db stats group-id artifact-id]
  (when-some [artifact (db/find-jar db group-id artifact-id)]
    (with-shields-io-img-src
      (auth/try-account
       #(view/show-jar db
                       stats
                       %
                       artifact
                       (db/recent-versions db group-id artifact-id 5)
                       (db/count-versions db group-id artifact-id))))))

(defn list-versions [db group-id artifact-id]
  (when-let [artifact (db/find-jar db group-id artifact-id)]
    (auth/try-account
     #(view/show-versions % artifact
                          (db/recent-versions db group-id artifact-id)))))

(defn list-dependents [db group-id artifact-id]
  (when-let [artifact (db/find-jar db group-id artifact-id)]
    (auth/try-account
     #(view/show-dependents db % artifact))))

(defn- show-version [db stats group-id artifact-id version]
  (when-some [artifact (db/find-jar db group-id artifact-id version)]
    (with-shields-io-img-src
      (auth/try-account
       #(view/show-jar db stats % artifact
                       (db/recent-versions db group-id artifact-id 5)
                       (db/count-versions db group-id artifact-id))))))

(defn response-based-on-format
  "render appropriate response based on the file type suffix provided:
  JSON or SVG"
  [db file-format artifact-id & [group-id]]
  (let [group-id (or group-id artifact-id)]
    (cond
      (= file-format "json") (-> (response/response (view/make-latest-version-json db group-id artifact-id))
                                 (response/header "Cache-Control" "no-cache")
                                 (response/content-type "application/json; charset=UTF-8")
                                 (response/header "Access-Control-Allow-Origin" "*"))
      (= file-format "svg") (-> (response/response (view/make-latest-version-svg db group-id artifact-id))
                                (response/header "Cache-Control" "no-cache")
                                (response/content-type "image/svg+xml")
                                (response/header "Access-Control-Allow-Origin" "*")))))

(defn routes [db stats]
  (compojure/routes
   (GET ["/:artifact-id", :artifact-id #"[^/]+"] [artifact-id]
        (show db stats artifact-id artifact-id))
   (GET ["/:group-id/:artifact-id", :group-id #"[^/]+" :artifact-id #"[^/]+"]
        [group-id artifact-id]
        (show db stats group-id artifact-id))

   (GET ["/:artifact-id/dependents" :artifact-id #"[^/]+"] [artifact-id]
        (list-dependents db artifact-id artifact-id))
   (GET ["/:group-id/:artifact-id/dependents"
         :group-id #"[^/]+" :artifact-id #"[^/]+"]
        [group-id artifact-id]
        (list-dependents db group-id artifact-id))

   (GET ["/:artifact-id/versions" :artifact-id #"[^/]+"] [artifact-id]
        (list-versions db artifact-id artifact-id))
   (GET ["/:group-id/:artifact-id/versions"
         :group-id #"[^/]+" :artifact-id #"[^/]+"]
        [group-id artifact-id]
        (list-versions db group-id artifact-id))

   (GET ["/:artifact-id/versions/:version"
         :artifact-id #"[^/]+" :version #"[^/]+"]
        [artifact-id version]
        (show-version db stats artifact-id artifact-id version))
   (GET ["/:group-id/:artifact-id/versions/:version"
         :group-id #"[^/]+" :artifact-id #"[^/]+" :version #"[^/]+"]
        [group-id artifact-id version]
        (show-version db stats group-id artifact-id version))

   (GET ["/:artifact-id/latest-version.:file-format"
         :artifact-id #"[^/]+"
         :file-format #"(svg|json)$"]
        [artifact-id file-format]
        (response-based-on-format db file-format artifact-id))

   (GET ["/:group-id/:artifact-id/latest-version.:file-format"
         :group-id #"[^/]+"
         :artifact-id #"[^/]+"
         :file-format #"(svg|json)$"]
        [group-id artifact-id file-format]
        (response-based-on-format db file-format artifact-id group-id))))
