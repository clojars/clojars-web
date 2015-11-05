(ns clojars.components.serial-sqlite
  (:require [clojars.db :as db]
            [clojars.db.sql :as sql]
            [com.stuartsierra.component :as component])
  (:import java.util.concurrent.Executors))

(def ^:private ^:dynamic *in-executor* nil)

(defn serialize-task* [task-name executor task]
  (if *in-executor*
    (task)
    (binding [*in-executor* true]
      (let [bound-f (bound-fn []
                      (try
                        (task)
                        (catch Throwable e
                          e)))
            response (deref
                      (.submit executor bound-f)
                      10000 ::timeout)]
        (cond
          (= response ::timeout) (throw
                                  (ex-info
                                   "Timed out waiting for serialized task to run"
                                   {:name task-name}))
          (instance? Throwable response) (throw
                                          (ex-info "Serialized task failed"
                                                   {:name task-name}
                                                   response))
          :default response)))))

(defmacro serialize-task [name & body]
  `(serialize-task* ~name ~'executor
                    (^:once fn* [] ~@body)))

(defrecord SerialSqlite [db executor]
  component/Lifecycle
  (start [this]
    (if executor
      this
      (assoc this :executor (Executors/newSingleThreadExecutor))))
  (stop [this]
    (when executor
      (.shutdown executor))
    (assoc this executor nil))
  db/Database
  (find-user [this username]
    (sql/find-user {:username username}
                   {:connection db
                    :result-set-fn first}))
  (find-user-by-user-or-email [this username-or-email]
    (sql/find-user-by-user-or-email {:username_or_email username-or-email}
                                    {:connection db
                                     :result-set-fn first}))
  (find-user-by-password-reset-code [this reset-code time]
    (sql/find-user-by-password-reset-code {:reset_code reset-code
                                           :reset_code_created_at time}
                                          {:connection db
                                           :result-set-fn first}))
  (jars-by-username [this username]
      (sql/jars-by-username {:username username}
                            {:connection db}))

  (add-user [this email username password pgp-key time]
    (let [record {:email email, :username username, :password password,
                :pgp_key pgp-key :created time}
        groupname (str "org.clojars." username)]
      (serialize-task :add-user
                      (sql/insert-user! record
                                        {:connection db})
                      (sql/insert-group! {:groupname groupname :username username}
                                         {:connection db}))
      record))
  (update-user [this account email username password pgp-key]
    (let [fields {:email email
                :username username
                :pgp_key pgp-key
                :account account
                :password password}]
      (serialize-task :update-user
                      (sql/update-user! fields
                                        {:connection db}))
      fields))
  (update-user-password [this reset-code password]
    (serialize-task :update-user-password
                    (sql/update-user-password! {:password password
                                                :reset_code reset-code}
                                               {:connection db})))
  (set-password-reset-code! [this username-or-email reset-code time]
    (serialize-task :set-password-reset-code
                    (sql/set-password-reset-code! {:reset_code reset-code
                                                   :reset_code_created_at time
                                                   :username_or_email username-or-email}
                                                  {:connection db})))

  (find-groupnames [this username]
    (sql/find-groupnames {:username username}
                         {:connection db
                          :row-fn :name}))
  (group-membernames [this groupname]
    (sql/group-membernames {:groupname groupname}
                           {:connection db
                            :row-fn :user}))
  (group-keys [this groupname]
    (sql/group-keys {:groupname groupname}
                    {:connection db
                     :row-fn :pgp_key}))
  (jars-by-groupname [this groupname]
    (sql/jars-by-groupname {:groupname groupname}
                           {:connection db}))
  (add-member [this groupname username added-by]
    (serialize-task :add-member
                    (sql/add-member! {:groupname groupname
                                      :username username
                                      :added_by added-by}
                                     {:connection db})))
  (delete-groups [this group-id]
    (serialize-task :delete-groups
                    (sql/delete-group! {:group_id group-id}
                                       {:connection db})))

  (recent-versions [this groupname jarname]
    (sql/recent-versions {:groupname groupname
                          :jarname jarname}
                         {:connection db}))
  (recent-versions [this groupname jarname num]
    (sql/recent-versions-limit {:groupname groupname
                                :jarname jarname
                                :num num}
                               {:connection db}))
  (count-versions [this groupname jarname]
    (sql/count-versions {:groupname groupname
                       :jarname jarname}
                      {:connection db
                       :result-set-fn first
                       :row-fn :count}))
  (recent-jars [this]
    (sql/recent-jars {} {:connection db}))
  (jar-exists [this groupname jarname]
    (sql/jar-exists {:groupname groupname
                   :jarname jarname}
                  {:connection db
                   :result-set-fn first
                   :row-fn #(= % 1)}))
  (find-jar [this groupname jarname]
    (sql/find-jar {:groupname groupname
                   :jarname jarname}
                  {:connection db
                   :result-set-fn first}))
  (find-jar [this groupname jarname version]
    (sql/find-jar-versioned {:groupname groupname
                             :jarname jarname
                             :version version}
                            {:connection db
                             :result-set-fn first}))
  (all-projects [this offset limit]
    (sql/all-projects {:num limit
                       :offset offset}
                      {:connection db}))
  (count-all-projects [this]
    (sql/count-all-projects {}
                            {:connection db
                             :result-set-fn first
                             :row-fn :count}))
  (count-projects-before [this s]
    (sql/count-projects-before {:s s}
                               {:connection db
                                :result-set-fn first
                                :row-fn :count}))

  (find-jars-information [this group-id]
    (db/find-jars-information this group-id nil))
  (find-jars-information [this group-id artifact-id]
    (if artifact-id
      (sql/find-jars-information {:group_id group-id
                                  :artifact_id artifact-id}
                                 {:connection db})
      (sql/find-groups-jars-information {:group_id group-id}
                                        {:connection db})))
  (promote [this group name version time]
    (serialize-task :promote
                    (sql/promote! {:group_id group
                                   :artifact_id name
                                   :version version
                                   :promoted_at time}
                                  {:connection db})))
  (promoted? [this group-id artifact-id version]
    (sql/promoted {:group_id group-id
                   :artifact_id artifact-id
                   :version version}
                  {:connection db
                   :result-set-fn first
                   :row-fn :promoted_at}))
  
  (add-jar [this account {:keys [group name version
                                 description homepage authors] :as jarmap} time]
    (serialize-task :add-jar
                    (sql/add-jar! {:groupname group
                                   :jarname   name
                                   :version   version
                                   :user      account
                                   :created    time
                                   :description description
                                   :homepage   homepage
                                   :authors    authors}
                                  {:connection db})))
  (delete-jars [this group-id]
    (serialize-task :delete-jars
                    (sql/delete-groups-jars! {:group_id group-id}
                                             {:connection db})))
  (delete-jars [this group-id jar-id]
    (serialize-task :delete-jars
                    (sql/delete-jars! {:group_id group-id
                                       :jar_id jar-id}
                                      {:connection db})))
  (delete-jars [this group-id jar-id version]
    (serialize-task :delete-jars
                    (sql/delete-jar-version! {:group_id group-id
                                              :jar_id jar-id
                                              :version version}
                                             {:connection db}))))

(defn connector []
  (map->SerialSqlite {}))
