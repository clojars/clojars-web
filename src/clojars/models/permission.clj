(ns clojars.models.permission
  (:require
   [clojars.db :as db]
   [next.jdbc :as jdbc]
   [next.jdbc.sql :as sql]))

(def SCOPE-ALL "*")
(def SCOPE-ANY ::scope-any)

(defn inactivate-member
  [db groupname scope username inactivated-by]
  (db/execute!
   db
   {:update :permissions
    :set    {:inactive       true
             :inactivated_by inactivated-by}
    :where  [:and
             [:= :user username]
             [:= :group_name groupname]
             [:= :scope scope]
             [:not [:is :inactive true]]]}))

(defn add-member*
  [db groupname scope username added-by admin?]
  {:pre [(some? scope)]}
  (sql/insert! db :permissions
               {:group_name groupname
                :scope      scope
                db/user-column username
                :added_by   added-by
                :admin      (boolean admin?)}))

(defn add-member
  [db groupname scope username added-by]
  (inactivate-member db groupname scope username added-by)
  (add-member* db groupname scope username added-by false))

(defn add-admin [db groupname scope username added-by]
  (inactivate-member db groupname scope username added-by)
  (add-member* db groupname scope username added-by true))

;; does not delete jars in the group. should it?
(defn delete-group
  [db groupname]
  (jdbc/with-transaction [tx db]
    (sql/delete! tx :permissions {:group_name groupname})
    (sql/delete! tx :group_verifications {:group_name groupname})))

(defn find-groupnames
  [db username]
  (mapv :group_name
        (db/q db
              {:select-distinct :group_name
               :from :permissions
               :where [:and
                       [:= :user username]
                       [:not [:is :inactive true]]]
               :order-by :group_name})))

(defn- with-scoping
  [scope where]
  {:pre [(some? scope)]}
  (cond-> where
    (not= SCOPE-ANY scope) (conj [:or
                                  [:= :scope SCOPE-ALL]
                                  [:= :scope scope]])))

(defn group-membernames
  [db groupname scope]
  (mapv :user
        (db/q db
              {:select-distinct :user
               :from :permissions
               :where
               (with-scoping scope
                 [:and
                  [:= :group_name groupname]
                  [:not [:is :inactive true]]
                  [:not [:is :admin true]]])})))

(defn group-adminnames
  [db groupname scope]
  (mapv :user
        (db/q db
              {:select-distinct :user
               :from :permissions
               :where
               (with-scoping scope
                 [:and
                  [:= :group_name groupname]
                  [:not [:is :inactive true]]
                  [:= :admin true]])})))

(defn group-admin-users
  [db groupname scope]
  (db/q db
        {:select [:email :created :id :user]
         :from :users
         :where [:in :user
                 {:select-distinct :user
                  :from :permissions
                  :where
                  (with-scoping scope
                    [:and
                     [:= :group_name groupname]
                     [:not [:is :inactive true]]
                     [:= :admin true]])}]}))

(defn group-activenames
  [db groupname]
  (mapv :user
        (db/q db
              {:select-distinct :user
               :from :permissions
               :where [:and
                       [:= :group_name groupname]
                       [:not [:is :inactive true]]]})))

(defn add-group
  "Adds a new group entry without any checks other than if it exists
  already. Intended to be used from clojars.admin and tests."
  [db account groupname]
  (let [actives (group-activenames db groupname)]
    (when (empty? actives)
      (add-admin db groupname SCOPE-ALL account "clojars"))))

(defn jar-active-usernames
  [db group-name scope]
  (into
   #{}
   (map :user)
   (db/q db
         {:select-distinct :user
          :from :permissions
          :where
          (with-scoping scope
            [:and
             [:= :group_name group-name]
             [:not [:is :inactive true]]])})))

(defn group-active-users
  [db groupname]
  (db/q db
        {:select :*
         :from :users
         :where [:in :user
                 {:select-distinct :user
                  :from :permissions
                  :where [:and
                          [:= :group_name groupname]
                          [:not [:is :inactive true]]]}]}))

(defn group-allnames
  [db groupname]
  (mapv :user
        (db/q db
              {:select-distinct :user
               :from :permissions
               :where [:= :group_name groupname]})))

(defn group-all-actives
  [db groupname]
  (db/q db
        {:select [:*]
         :from :permissions
         :where [:and
                 [:= :group_name groupname]
                 [:not [:is :inactive true]]]
         :order-by [:user :scope]}))

(defn admin-group-scopes-for-user
  [db username groupname]
  (into #{}
        (map :scope)
        (db/q db
              {:select :scope
               :from :permissions
               :where
               [:and
                [:= :group_name groupname]
                [:= :user username]
                [:= :admin true]
                [:not [:is :inactive true]]]})))

(defn user-has-all-scope?
  [db username groupname]
  (-> (db/q db
            {:select :user
             :from :permissions
             :where
             [:and
              [:= :group_name groupname]
              [:= :user username]
              [:not [:is :inactive true]]
              [:= :scope SCOPE-ALL]]
             :limit 1})
      (first)
      (some?)))

(defn group-actives
  [db groupname]
  (db/q db
        {:select :*
         :from :permissions
         :where [:and
                 [:= :group_name groupname]
                 [:not [:is :inactive true]]]
         :order-by [:user :scope]}))

(defn group-actives-for-user
  [db groupname username]
  (db/q db
        {:select :*
         :from :permissions
         :where [:and
                 [:= :group_name groupname]
                 [:not [:is :inactive true]]
                 [:or
                  ;; user has all scope, so can see all other users
                  [:= 1
                   {:select 1
                    :from :permissions
                    :where
                    [:and
                     [:= :group_name groupname]
                     [:= :user username]
                     [:not [:is :inactive true]]
                     [:= :scope SCOPE-ALL]]
                    :limit 1}]
                  ;; user has a limited scope, so should only see users within
                  ;; their scope
                  [:in :scope
                   {:select-distinct :scope
                    :from :permissions
                    :where
                    [:and
                     [:= :group_name groupname]
                     [:= :user username]
                     [:not [:is :inactive true]]]}]]]
         :order-by [:user :scope]}))
