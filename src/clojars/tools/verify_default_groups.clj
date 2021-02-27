(ns clojars.tools.verify-default-groups
  "This is a one-off tool to:
  - add verification for all org.clojars.<username> groups
  - create and add verification for net.clojars.<username> groups for all users"
  (:require
   [clojars.config :as config]
   [clojars.db :as db]
   [clojure.java.jdbc :as jdbc]))

(defn- get-all-usernames
  [db]
  (jdbc/query
   db
   ["select \"user\" from users"]
   :row-fn :user))

(defn -main [& _]
  (let [db (:db (config/config))
        usernames (get-all-usernames db)]
    (printf "Migrating %s users\n" (count usernames))
    (doseq [username usernames
            group-prefix ["org.clojars." "net.clojars."]
            :let [group-name (str group-prefix username)]]
      (printf "> %s : %s\n" username group-name)
      (let [group-admins (db/group-adminnames db group-name)]
        (cond
          (some #{username} group-admins)
          (println ">> group exists and user is admin")

          (seq group-admins)
          (println "!!> group exists but user isn't admin")

          :else
          (do
            (println "!> adding group")
            (db/check-and-add-group db username group-name)))

        (let [group-admins (db/group-adminnames db group-name)]
          (if (some #{username} group-admins)
            (do
              (println ">> verifying group")
              (db/verify-group! db username group-name))
            (println "!!> user isn't admin for group, skipping verification")))))))
