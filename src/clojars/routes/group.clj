(ns clojars.routes.group
  (:require [clojars
             [auth :as auth]
             [db :as db]]
            [clojars.web.group :as view]
            [compojure.core :as compojure :refer [GET POST DELETE]]))

(defn routes [db]
  (compojure/routes
   (GET ["/groups/:groupname", :groupname #"[^/]+"] [groupname]
     (let [actives (seq (db/group-actives db groupname))]
        (when (seq actives)
          (auth/try-account
           #(view/show-group db % groupname actives)))))
   (POST ["/groups/:groupname", :groupname #"[^/]+"] [groupname username admin]
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
                 (if (= admin "1")
                   #(cond
                      (= account username)
                      (view/show-group db account groupname actives
                        "Cannot change your own membership!")

                      (some #{username} adminnames)
                      (view/show-group db account groupname actives
                        "They're already an admin!")

                      (db/find-user db username)
                      (do (db/add-admin db groupname username account)
                        (view/show-group db account groupname
                          (into (remove (fn [active] (= username (:user active))) actives) [{:user username :admin 1}])))

                      :else
                      (view/show-group db account groupname actives
                        (str "No such user: "
                          username)))
                   #(cond
                      (= account username)
                      (view/show-group db account groupname actives
                        "Cannot change your own membership!")

                      (some #{username} membernames)
                      (view/show-group db account groupname actives
                        "They're already a member!")

                      (db/find-user db username)
                      (do (db/add-member db groupname username account)
                        (view/show-group db account groupname
                          (into (remove (fn [active] (= username (:user active))) actives) [{:user username :admin 0}])))

                      :else
                      (view/show-group db account groupname actives
                        (str "No such user: "
                          username))))))))))
   (DELETE ["/groups/:groupname", :groupname #"[^/]+"] [groupname username]
     (let [actives (seq (db/group-actives db groupname))]
       (when (seq actives)
           (auth/try-account
             (fn [account]
               (auth/require-authorization
                 db
                 account
                 groupname
                 #(cond
                    (= account username)
                    (view/show-group db account groupname actives
                      "Cannot remove yourself!")

                    (some #{username} (map :user actives))
                    (do (db/inactivate-member db groupname username account)
                      (view/show-group db account groupname
                        (remove (fn [active] (= username (:user active))) actives)))

                    :else
                    (view/show-group db account groupname actives
                      (str "No such member: "
                        username)))))))))))
