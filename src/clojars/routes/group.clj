(ns clojars.routes.group
  (:require [clojars
             [auth :as auth]
             [db :as db]]
            [clojars.web.group :as view]
            [compojure.core :as compojure :refer [GET POST]]))

(defn routes [db]
  (compojure/routes
   (GET ["/groups/:groupname", :groupname #"[^/]+"] [groupname]
     (let [actives (seq (db/group-actives db groupname))]
        (when (seq actives)
          (auth/try-account
           #(view/show-group db % groupname actives)))))
   (POST ["/groups/:groupname", :groupname #"[^/]+"] [groupname username]
     (let [actives (seq (db/group-actives db groupname))
           membernames (->> actives
                            (filter #(not= 1 (:admin %)))
                            (map :user))
           adminnames (->> actives
                            (filter #(= 1 (:admin %)))
                            (map :user))]
       (when (seq actives)
         (auth/try-account
           (fn [account]
             (auth/require-authorization
               db
               account
               groupname
               #(cond
                  (some #{username} membernames)
                  (view/show-group db account groupname actives
                    "They're already a member!")

                  (some #{username} membernames)
                  (view/show-group db account groupname actives
                    "They're already a admin!")

                  (db/find-user db username)
                  (do (db/add-member db groupname username account)
                    (view/show-group db account groupname
                      (conj actives {:user username :admin 0})))

                  :else
                  (view/show-group db account groupname actives
                    (str "No such user: "
                      username)))))))))))
