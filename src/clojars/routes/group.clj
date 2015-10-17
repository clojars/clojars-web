(ns clojars.routes.group
  (:require [clojars
             [auth :as auth]
             [db :as db]]
            [clojars.web.group :as view]
            [compojure.core :as compojure :refer [GET POST]]))

(defn routes [db]
  (compojure/routes
   (GET ["/groups/:groupname", :groupname #"[^/]+"] [groupname]
        (if-let [membernames (seq (db/group-membernames db groupname))]
          (auth/try-account
           (view/show-group db account groupname membernames))))
   (POST ["/groups/:groupname", :groupname #"[^/]+"] [groupname username]
         (if-let [membernames (seq (db/group-membernames db groupname))]
           (auth/try-account
            (auth/require-authorization
             db
             groupname
             (cond
               (some #{username} membernames)
               (view/show-group db account groupname membernames
                                "They're already a member!")
               (db/find-user db username)
               (do (db/add-member db groupname username account)
                   (view/show-group db account groupname
                                    (conj membernames username)))
               :else
               (view/show-group db account groupname membernames
                                (str "No such user: "
                                     username)))))))))
