(ns clojars.models.audit
  (:require
   [clojars.db :as db]
   [next.jdbc.sql :as sql]))

(defn add-audit
  [db tag username group-name jar-name version message]
  (sql/insert! db :audit
               {:tag        tag
                db/user-column username
                :group_name group-name
                :jar_name   jar-name
                :version    version
                :message    message}))

(defn find-audit
  [db {:keys [username group-name jar-name version]}]
  (when-some [where (cond
                      version    [:and
                                  [:= :group_name group-name]
                                  [:= :jar_name jar-name]
                                  [:= :version version]]
                      jar-name   [:and
                                  [:= :group_name group-name]
                                  [:= :jar_name jar-name]]
                      group-name [:= :group_name group-name]
                      username   [:= :user username]
                      :else      nil)]
    (db/q db
          {:select :*
           :from :audit
           :where where
           :order-by [[:created :desc]]})))
