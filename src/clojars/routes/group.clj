(ns clojars.routes.group
  (:require
   [clojars.auth :as auth]
   [clojars.db :as db]
   [clojars.web.group :as view]
   [compojure.core :as compojure :refer [GET POST DELETE]]))

(defn- get-members [db groupname]
  (let [actives (seq (db/group-actives db groupname))]
    (when (seq actives)
      (auth/try-account
        #(view/show-group db % groupname actives)))))

(defn- toggle-or-add-member [db groupname username make-admin?]
  (let [actives (seq (db/group-actives db groupname))
        membernames (->> actives
                      (filter (complement view/is-admin?))
                      (map :user))
        adminnames (->> actives
                     (filter view/is-admin?)
                     (map :user))]
    (when (seq actives)
      (auth/try-account
        (fn [account]
          (auth/require-admin-authorization
            db
            account
            groupname
            (if make-admin?
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
                       (into (remove (fn [active] (= username (:user active))) actives) [{:user username :admin true}])))

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
                       (into (remove (fn [active] (= username (:user active))) actives) [{:user username :admin false}])))

                 :else
                 (view/show-group db account groupname actives
                   (str "No such user: "
                     username))))))))))

(defn- remove-member [db groupname username]
  (let [actives (seq (db/group-actives db groupname))]
    (when (seq actives)
      (auth/try-account
        (fn [account]
          (auth/require-admin-authorization
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
                   username)))))))))

(defn routes [db]
  (compojure/routes
    (GET ["/groups/:groupname", :groupname #"[^/]+"] [groupname]
      (get-members db groupname))
    (POST ["/groups/:groupname", :groupname #"[^/]+"] [groupname username admin]
      (toggle-or-add-member db groupname username (= "1" admin)))
    (DELETE ["/groups/:groupname", :groupname #"[^/]+"] [groupname username]
      (remove-member db groupname username))))
