(ns clojars.auth
  (:require [cemerick.friend :as friend]
            [clojars.db :as db]))

(defn try-account [f]
  (f (:username (friend/current-authentication))))

(defn with-account [f]
  (friend/authenticated (try-account f)))

(defn authorized-admin? [db account group]
  (when account
    (let [adminnames (db/group-adminnames db group)
          allnames (db/group-allnames db group)]
      (or (some #{account} adminnames) (empty? allnames)))))

(defn authorized-member? [db account group]
  (when account
    (some #{account} (db/group-membernames db group))))

(defn require-admin-authorization [db account group f]
  (if (authorized-admin? db account group)
    (f)
    (friend/throw-unauthorized friend/*identity*
      {:cemerick.friend/required-roles group})))
