(ns clojars.routes.group
  (:require [compojure.core :refer [GET POST defroutes]]
            [clojars.web.group :as view]
            [clojars.db :as db]
            [clojars.auth :as auth]))

(defroutes routes
  (GET ["/groups/:groupname", :groupname #"[^/]+"] [groupname]
       (if-let [membernames (db/group-membernames groupname)]
         {:body (auth/try-account
                 (view/show-group account groupname membernames))
          :headers {"X-Frame-Options" "DENY"}
          :status 200}))
  (POST ["/groups/:groupname", :groupname #"[^/]+"] [groupname username]
        (if-let [membernames (db/group-membernames groupname)]
          (auth/try-account
           (auth/require-authorization
            groupname
            (cond
             (some #{username} membernames)
             (view/show-group account groupname membernames
                              "They're already a member!")
             (db/find-user username)
             (do (db/add-member groupname username account)
                 (view/show-group account groupname
                                  (conj membernames username)))
             :else
             (view/show-group account groupname membernames
                              (str "No such user: "
                                   username))))))))
