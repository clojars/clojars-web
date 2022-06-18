(ns clojars.routes.group
  (:require
   [clojars.auth :as auth]
   [clojars.db :as db]
   [clojars.web.group :as view]
   [compojure.core :as compojure :refer [GET POST DELETE]]
   [clojars.log :as log]))

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
                        (map :user))
        handler-fn (fn [account admin? group-users]
                     #(log/with-context {:tag :toggle-or-add-group-member
                                         :group groupname
                                         :username account
                                         :username-to-add username
                                         :admin? admin?}
                        (cond
                          (= account username)
                          (do
                            (log/info {:status :failed
                                       :reason :self-toggle})
                            (view/show-group db account groupname actives
                                             "Cannot change your own membership!"))

                          (some #{username} group-users)
                          (do
                            (log/info {:status :failed
                                       :reason :user-already-member})
                            (view/show-group db account groupname actives
                                             (format "They're already an %s!" (if admin? "admin" "member"))))

                          (db/find-user db username)
                          (let [add-fn (if admin? db/add-admin db/add-member)]
                            (add-fn db groupname username account)
                            (log/info {:status :success})
                            (log/audit db {:tag :member-added
                                           :message (format "user '%s' added as %s" username
                                                            (if admin? "admin" "member"))})
                            (view/show-group db account groupname
                                             (into (remove (fn [active]
                                                             (= username (:user active))) actives)
                                                   [{:user username :admin admin?}])))

                          :else
                          (do
                            (log/info {:status :failed
                                       :reason :user-not-found})
                            (view/show-group db account groupname actives
                                             (str "No such user: "
                                                  username))))))]
    (when (seq actives)
      (auth/try-account
       (fn [account]
         (auth/require-admin-authorization
          db
          account
          groupname
          (handler-fn account
                      make-admin?
                      (if make-admin? adminnames membernames))))))))

(defn- remove-member [db groupname username]
  (let [actives (seq (db/group-actives db groupname))]
    (when (seq actives)
      (auth/try-account
       (fn [account]
         (auth/require-admin-authorization
          db
          account
          groupname
          #(log/with-context {:tag :remove-group-member
                              :username account
                              :group groupname
                              :username-to-remove username}
             (cond
               (= account username)
               (do
                 (log/info {:status :failed
                            :reason :self-removal})
                 (view/show-group db account groupname actives
                                  "Cannot remove yourself!"))

               (some #{username} (map :user actives))
               (do
                 (db/inactivate-member db groupname username account)
                 (log/info {:status :success})
                 (log/audit db {:tag :member-removed
                                :message (format "user '%s' removed" username)})
                 (view/show-group db account groupname
                                  (remove (fn [active] (= username (:user active))) actives)))

               :else
               (do
                 (log/info {:status :failed
                            :reason :member-not-in-group})
                 (view/show-group db account groupname actives
                                  (str "No such member: "
                                       username)))))))))))

(defn routes [db]
  (compojure/routes
   (GET ["/groups/:groupname", :groupname #"[^/]+"] [groupname]
        (get-members db groupname))
   (POST ["/groups/:groupname", :groupname #"[^/]+"] [groupname username admin]
         (toggle-or-add-member db groupname username (= "1" admin)))
   (DELETE ["/groups/:groupname", :groupname #"[^/]+"] [groupname username]
           (remove-member db groupname username))))
