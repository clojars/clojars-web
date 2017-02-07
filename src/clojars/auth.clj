(ns clojars.auth
  (:require [cemerick.friend :as friend]
            [clojars.db :refer [group-adminnames]]))

(defn try-account [f]
  (f (:username (friend/current-authentication))))

(defn with-account [f]
  (friend/authenticated (try-account f)))

(defn authorized? [db account group]
  (when account
    (let [names (group-adminnames db group)]
      (or (some #{account} names) (empty? names)))))

(defn require-authorization [db account group f]
  (if (authorized? db account group)
    (f)
    (friend/throw-unauthorized friend/*identity*
      {:cemerick.friend/required-roles group})))
