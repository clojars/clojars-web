(ns clojars.auth
  (:require [cemerick.friend :as friend]
            [clojars.db :refer [group-membernames]]))

(defmacro with-account [body]
  `(friend/authenticated (try-account ~body)))

(defmacro try-account [body]
  `(let [~'account (:username (friend/current-authentication))]
     ~body))

(defn authorized? [db account group]
  (if account
    (let [names (group-membernames db group)]
      (or (some #{account} names) (empty? names)))))

(defmacro require-authorization [db group & body]
  `(if (authorized? ~db ~'account ~group)
     (do ~@body)
     (friend/throw-unauthorized friend/*identity*
                                {:cemerick.friend/exprs (quote [~@body])
                                 :cemerick.friend/required-roles ~group})))
