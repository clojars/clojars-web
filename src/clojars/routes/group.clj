(ns clojars.routes.group
  (:require [clojars
             [auth :as auth]
             [db :as db]]
            [clojars.web.group :as view]
            [compojure.core :as compojure :refer [GET POST DELETE]]))

(defn routes [db]
  (compojure/routes
   (GET ["/groups/:group-id", :group-id #"[^/]+"] [group-id]
     (let [actives (seq (db/group-actives db group-id))]
        (when (seq actives)
          (auth/try-account
           #(view/show-group db % group-id actives)))))
   (POST ["/groups/:group-id", :group-id #"[^/]+"] [group-id username admin]
     (let [actives (seq (db/group-actives db group-id))
           membernames (->> actives
                            (filter #(not= 1 (:admin %)))
                            (map :user))
           adminnames (->> actives
                            (filter #(= 1 (:admin %)))
                            (map :user))]
       (when (seq actives)
           (auth/try-account
             (fn [account]
               (auth/require-admin-authorization
                 db
                 account
                 group-id
                 (if (= admin "1")
                   #(cond
                      (= account username)
                      (view/show-group db account group-id actives
                        "Cannot change your own membership!")

                      (some #{username} adminnames)
                      (view/show-group db account group-id actives
                        "They're already an admin!")

                      (db/find-user db username)
                      (do (db/add-admin db group-id username account)
                        (view/show-group db account group-id
                          (into (remove (fn [active] (= username (:user active))) actives) [{:user username :admin 1}])))

                      :else
                      (view/show-group db account group-id actives
                        (str "No such user: "
                          username)))
                   #(cond
                      (= account username)
                      (view/show-group db account group-id actives
                        "Cannot change your own membership!")

                      (some #{username} membernames)
                      (view/show-group db account group-id actives
                        "They're already a member!")

                      (db/find-user db username)
                      (do (db/add-member db group-id username account)
                        (view/show-group db account group-id
                          (into (remove (fn [active] (= username (:user active))) actives) [{:user username :admin 0}])))

                      :else
                      (view/show-group db account group-id actives
                        (str "No such user: "
                          username))))))))))
   (DELETE ["/groups/:group-id", :group-id #"[^/]+"] [group-id username]
     (let [actives (seq (db/group-actives db group-id))]
       (when (seq actives)
           (auth/try-account
             (fn [account]
               (auth/require-admin-authorization
                 db
                 account
                 group-id
                 #(cond
                    (= account username)
                    (view/show-group db account group-id actives
                      "Cannot remove yourself!")

                    (some #{username} (map :user actives))
                    (do (db/inactivate-member db group-id username account)
                      (view/show-group db account group-id
                        (remove (fn [active] (= username (:user active))) actives)))

                    :else
                    (view/show-group db account group-id actives
                      (str "No such member: "
                        username)))))))))))
