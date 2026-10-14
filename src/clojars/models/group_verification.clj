(ns clojars.models.group-verification
  (:require
   [clojars.db :as db]
   [clojars.models.permission :as permission-m]
   [clojure.string :as str]
   [next.jdbc.sql :as sql]))

(defn find-group-verification
  [db group-name]
  (first
   (db/q db
         {:select :*
          :from :group_verifications
          :where [:= :group_name group-name]
          :limit 1})))

(defn find-group-verifications-for-users-groups
  [db username]
  (db/q db
        {:select :*
         :from :group_verifications
         :where [:in :group_name
                 {:select-distinct :group_name
                  :from :permissions
                  :where [:and
                          [:= :user username]
                          [:not [:is :inactive true]]]}]}))

(defn verify-group!
  [db username group-name]
  (when-not (find-group-verification db group-name)
    (sql/insert! db :group_verifications
                 {:group_name group-name
                  :verified_by username})))

(defn- group-names-for-provider
  [provider provider-username]
  (for [prefix
        (case provider
          "GitHub" ["com.github."
                    "io.github."]
          "GitLab" ["com.gitlab."
                    "io.gitlab."])]
    (str prefix (str/lower-case provider-username))))

(defn maybe-verify-provider-groups
  "Will add and verify groups that are provider specific (github,
  gitlab, etc). Will only add the group if it doesn't already
  exist. Will only verify the group if it isn't already verified and
  the user is a member of the group."
  [db {:keys [auth-provider provider-login username]}]
  (when (and auth-provider
             provider-login
             username)
    (let [results
          (for [group-name (group-names-for-provider auth-provider provider-login)]
            (when (not (find-group-verification db group-name))
              (let [actives (permission-m/group-activenames db group-name)]
                (cond
                  (empty? actives)
                  (do
                    (permission-m/add-admin db group-name permission-m/SCOPE-ALL
                                            username "clojars")
                    (verify-group! db username group-name)
                    nil)

                  (some #{username} actives)
                  (do
                    (verify-group! db username group-name)
                    nil)

                  :else
                  group-name))))]
      (into []
            (comp (remove nil?)
                  (map (fn [group-name]
                         {:tag :provider-group-verification-user-not-member
                          :user username
                          :group group-name})))
            results))))
