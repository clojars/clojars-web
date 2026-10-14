(ns clojars.models.group-setting
  (:require
   [clojars.db :as db]))

(defn set-group-mfa-required
  [db group-id required?]
  (db/execute!
   db
   {:insert-into   :group_settings
    :values        [{:group_name            group-id
                     :require_mfa_to_deploy (boolean required?)}]
    ;; update if settings already exist
    :on-conflict   [:group_name]
    :do-update-set {:require_mfa_to_deploy (boolean required?)}}))

(defn get-group-settings
  [db group-id]
  (first
   (db/q db
         {:select :*
          :from :group_settings
          :where [:= :group_name group-id]
          :limit 1})))
