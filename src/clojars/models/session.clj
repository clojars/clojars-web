(ns clojars.models.session
  (:require
   [clojars.db :as db]
   [jdbc-ring-session.core :as jdbc-ring-session]))

(defn delete-sessions-for-user!
  "Deletes all active sessions for `username`. Used to invalidate other
  active sessions after a password change so an attacker who got in via
  the old password is kicked out."
  [db username]
  (let [session-ids
        (into #{}
              (comp
               (map #(update % :value jdbc-ring-session/deserialize-postgres))
               (filter #(= username
                           (get-in % [:value :cemerick.friend/identity :current])))
               (map :session_id))
              (db/q db {:select :* :from :session_store}))]
    (when (seq session-ids)
      (db/execute! db {:delete-from :session_store
                       :where [:in :session_id session-ids]}))))

(defn clear-sessions!
  "Clears all active sessions. Should only be used in testing!"
  [db]
  (db/execute! db {:delete-from :session_store}))
