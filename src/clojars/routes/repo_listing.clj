(ns clojars.routes.repo-listing
  (:require
   [clojars.web.repo-listing :as repo-listing]
   [compojure.core :as compojure :refer [GET HEAD]]
   [ring.util.response :as ring.response]))

(defn- repo-listing
  [repo-bucket path]
  (-> (repo-listing/index repo-bucket path)
      (ring.response/response)
      (ring.response/content-type "text/html;charset=utf-8")
      ;; Instruct fastly to cache this result for 15 minutes
      (ring.response/header "Cache-Control" "s-maxage=900")))

(defn routes
  [repo-bucket]
  (compojure/routes
   (GET ["/repo-listing"]
        {{:keys [path]} :params}
        (repo-listing repo-bucket path))
   (HEAD ["/repo-listing"]
         {{:keys [path]} :params}
         (repo-listing repo-bucket path))))
