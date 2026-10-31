(ns clojars.routes.artifact
  (:require
   [clojars.auth :as auth]
   [clojars.http-utils :as http-utils]
   [clojars.models.jar :as jar-m]
   [clojars.web.jar :as view]
   [compojure.core :as compojure :refer [GET]]
   [ring.util.response :as response]))

(defn- with-shields-io-img-src
  "Allows shields.io badges to be shown on artifact pages."
  [body]
  (http-utils/with-extra-csp-srcs {:img-src ["https://img.shields.io"]} body))

(defn show [db stats group-id artifact-id]
  (when-some [artifact (jar-m/find-jar db group-id artifact-id)]
    (with-shields-io-img-src
      (auth/try-account
       #(view/show-jar db
                       stats
                       %
                       artifact
                       (jar-m/recent-versions db group-id artifact-id 5)
                       (jar-m/count-versions db group-id artifact-id))))))

(defn list-versions [db group-id artifact-id]
  (when-let [artifact (jar-m/find-jar db group-id artifact-id)]
    (auth/try-account
     #(view/show-versions % artifact
                          (jar-m/recent-versions db group-id artifact-id)))))

(defn list-dependents [db group-id artifact-id]
  (when-let [artifact (jar-m/find-jar db group-id artifact-id)]
    (auth/try-account
     #(view/show-dependents db % artifact))))

(defn- show-version [db stats group-id artifact-id version]
  (when-some [artifact (jar-m/find-jar db group-id artifact-id version)]
    (with-shields-io-img-src
      (auth/try-account
       #(view/show-jar db stats % artifact
                       (jar-m/recent-versions db group-id artifact-id 5)
                       (jar-m/count-versions db group-id artifact-id))))))

(defn latest-version-response
  "render appropriate response based on the file type suffix provided:
  JSON or SVG"
  [db file-format artifact-id & [group-id]]
  (let [group-id (or group-id artifact-id)
        [content-type body]
        (case file-format
          "json" ["application/json; charset=UTF-8" (view/make-latest-version-json db group-id artifact-id)]
          "svg"  ["image/svg+xml" (view/make-latest-version-svg db group-id artifact-id)])]
    (-> (response/response body)
        (response/content-type content-type)
        (response/header "Cache-Control" "no-cache")
        (response/header "Access-Control-Allow-Origin" "*"))))

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
        (latest-version-response db file-format artifact-id))

   (GET ["/:group-id/:artifact-id/latest-version.:file-format"
         :group-id #"[^/]+"
         :artifact-id #"[^/]+"
         :file-format #"(svg|json)$"]
        [group-id artifact-id file-format]
        (latest-version-response db file-format artifact-id group-id))))
