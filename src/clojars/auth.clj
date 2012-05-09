(ns clojars.auth
  (:require [cemerick.friend :as friend]
            [clojars.db :refer [group-membernames]]))

(defmacro with-account [body]
  `(friend/authenticated (try-account ~body)))

(defmacro try-account [body]
  `(let [~'account (:username (friend/current-authentication))]
     ~body))

(defn authorized? [account group]
  (let [names# (group-membernames group)]
    (or (some #{account} names#) (empty? names#))))

(defmacro require-authorization [group & body]
  `(if (authorized? ~'account ~group)
       (do ~@body)
       (friend/throw-unauthorized friend/*identity*)))