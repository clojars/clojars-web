(ns clojars.routes.repo-listing
  (:require
   [clojars.web.repo-listing :as repo-listing]
   [compojure.core :as compojure :refer [GET HEAD]]))

(defn routes
  [repo-bucket]
  (compojure/routes
   (GET ["/list-repo"]
        {{:keys [path]} :params}
        (repo-listing/response repo-bucket path))
   (HEAD ["/list-repo"]
         {{:keys [path]} :params}
         (repo-listing/response repo-bucket path))))
