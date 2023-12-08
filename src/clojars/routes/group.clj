(ns clojars.routes.group
  (:require
   [clojars.auth :as auth]
   [clojars.db :as db]
   [clojars.event :as event]
   [clojars.log :as log]
   [clojars.routes.common :as common]
   [clojars.web.group :as view]
   [compojure.core :as compojure :refer [GET POST DELETE]]))

(defn- get-members [db groupname]
  (when (seq (db/group-all-actives db groupname))
    (auth/try-account
     #(view/show-group db % groupname))))

(defn- toggle-or-add-member
  [db event-emitter groupname username make-admin? scope-to-jar details]
  (let [actives (db/group-all-actives db groupname)
        user-to-add (db/find-user db username)]
    (when (seq actives)
      (auth/try-account
       (fn [account]
         (auth/require-admin-authorization
          db
          account
          groupname
          scope-to-jar
          (fn []
            (log/with-context {:tag :toggle-or-add-group-permission
                               :group groupname
                               :username account
                               :username-to-add username
                               :make-admin? make-admin?}
              (cond
                (= account username)
                (do
                  (log/info {:status :failed
                             :reason :self-toggle})
                  (view/show-group db account groupname
                                   "Cannot change your own permission"))

                (some #{[username scope-to-jar make-admin?]}
                      (mapv (juxt :user :scope :admin) actives))
                (do
                  (log/info {:status :failed
                             :reason :user-already-permission})
                  (view/show-group
                   db account groupname
                   (format "User already in group with %s scope as %s"
                           scope-to-jar
                           (if make-admin? "admin" "member"))))

                (and (not= scope-to-jar db/SCOPE-ALL)
                     (some #{[username db/SCOPE-ALL]}
                           (mapv (juxt :user :scope) actives)))
                (do
                  (log/info {:status :failed
                             :reason :user-already-has-all-scope})
                  (view/show-group
                   db account groupname
                   (format "User has '%s' scope, so can't be further scoped"
                           db/SCOPE-ALL)))

                (and (= scope-to-jar db/SCOPE-ALL)
                     (seq (into []
                                (comp
                                 (filter #(= username (:user %)))
                                 (map :scope)
                                 (remove #(= db/SCOPE-ALL %)))
                                actives)))
                (do
                  (log/info {:status :failed
                             :reason :user-already-has-project-scope})
                  (view/show-group
                   db account groupname
                   (format "User has project scope, so can't be given '%s' scope"
                           db/SCOPE-ALL)))

                user-to-add
                (let [add-fn (if make-admin? db/add-admin db/add-member)]
                  (add-fn db groupname scope-to-jar username account)
                  (event/emit event-emitter
                              :group-permission-added
                              (merge
                               details
                               {:admin? make-admin?
                                :admin-emails (db/group-admin-emails db groupname scope-to-jar)
                                :group groupname
                                :scope-to-jar scope-to-jar
                                :member username
                                :member-email (:email user-to-add)
                                :username account}))
                  (log/info {:status :success})
                  (log/audit db {:tag :permission-added
                                 :message (format "user '%s' added as %s with '%s' scope"
                                                  username
                                                  (if make-admin? "admin" "member")
                                                  scope-to-jar)})
                  (view/show-group db account groupname))

                :else
                (do
                  (log/info {:status :failed
                             :reason :user-not-found})
                  (view/show-group db account groupname
                                   (str "No such user: "
                                        username))))))))))))

(defn- remove-member
  [db event-emitter groupname scope-to-jar username details]
  (let [actives (db/jar-active-usernames db groupname scope-to-jar)]
    (when (seq actives)
      (auth/try-account
       (fn [account]
         (auth/require-admin-authorization
          db
          account
          groupname
          scope-to-jar
          #(log/with-context {:tag :remove-group-permission
                              :username account
                              :group groupname
                              :scope-to-jar scope-to-jar
                              :username-to-remove username}
             (cond
               (= account username)
               (do
                 (log/info {:status :failed
                            :reason :self-removal})
                 (view/show-group db account groupname
                                  "Cannot remove yourself!"))

               (contains? actives username)
               (do
                 (db/inactivate-member db groupname scope-to-jar username account)
                 (event/emit event-emitter
                             :group-permission-removed
                             (merge
                              details
                              {:admin-emails (db/group-admin-emails db groupname scope-to-jar)
                               :group groupname
                               :scope-to-jar scope-to-jar
                               :member username
                               :member-email (:email (db/find-user db username))
                               :username account}))
                 (log/info {:status :success})
                 (log/audit db {:tag :permission-removed
                                :message (format "user '%s' with scope '%s' removed"
                                                 username
                                                 scope-to-jar)})
                 (view/show-group db account groupname))

               :else
               (do
                 (log/info {:status :failed
                            :reason :permission-not-in-group})
                 (view/show-group db account groupname
                                  (str "No such permission in group: "
                                       username)))))))))))

(defn- update-group-settings
  [db event-emitter groupname require-mfa? details]
  (let [actives (seq (db/group-all-actives db groupname))
        settings {:require_mfa_to_deploy require-mfa?}]
    (auth/try-account
     (fn [account]
       (auth/require-admin-authorization
        db
        account
        groupname
        db/SCOPE-ALL
        #(log/with-context {:tag :update-group-settings
                            :username account
                            :group groupname
                            :settings settings}
           (if actives
             (do
               (db/set-group-mfa-required db groupname require-mfa?)
               (event/emit event-emitter
                           :group-settings-updated
                           (merge
                            details
                            ;; This email goes to any admin that has any scope
                            ;; since this change will impact all deploys within
                            ;; the group
                            {:admin-emails (db/group-admin-emails db groupname db/SCOPE-ALL)
                             :group groupname
                             :username account
                             :settings settings}))
               (log/info {:status :success})
               (log/audit db {:tag :group-settings-updated
                              :message (format "settings updated: %s" (pr-str settings))})
               (view/show-group db account groupname))
             (view/show-group db account groupname
                              "Cannot set settings for non-existent group"))))))))

(defn routes [db event-emitter]
  (compojure/routes
   (GET ["/groups/:groupname", :groupname #"[^/]+"] [groupname]
        (get-members db groupname))
   (POST ["/groups/:groupname/settings", :groupname #"[^/]+"] [groupname require_mfa :as request]
         (update-group-settings db event-emitter groupname (= "1" require_mfa) (common/request-details request)))
   (POST ["/groups/:groupname", :groupname #"[^/]+"] [admin groupname scope_to_jar scope_to_jar_new username :as request]
         (toggle-or-add-member db event-emitter groupname username (= "1" admin)
                               (if (= ":new" scope_to_jar)
                                 scope_to_jar_new
                                 scope_to_jar)
                               (common/request-details request)))
   (DELETE ["/groups/:groupname", :groupname #"[^/]+"] [groupname scope_to_jar username :as request]
           (remove-member db event-emitter groupname scope_to_jar username (common/request-details request)))))
