(ns clojars.auth
  (:require [cemerick.friend :as friend]
            [clojars.db :refer [group-membernames find-user-by-user-or-email]]))

(defmacro with-account [body]
  `(friend/authenticated (try-account ~body)))

(defmacro try-account [body]
  `(let [~'account (:username (friend/current-authentication))]
     ~body))

(defn get-user [id]
  (when-let [{:keys [user password]}
             (find-user-by-user-or-email id)]
    (when (not (empty? password))
      {:username user :password password})))

(defn authorized? [account group]
  (if account
    (let [names (group-membernames group)]
      (or (some #{account} names) (empty? names)))))

(defmacro require-authorization [group & body]
  `(if (authorized? ~'account ~group)
       (do ~@body)
       (friend/throw-unauthorized friend/*identity*)))