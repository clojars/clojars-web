(ns clojars.auth
  (:require [cemerick.friend :as friend]
            [clojars.db :refer [group-membernames]]))

(defmacro with-account [body]
  `(friend/authenticated (try-account ~body)))

(defmacro try-account [body]
  `(let [~'account (:username (friend/current-authentication))]
     ~body))

(defmacro authorized! [group & body]
  `(let [names# (group-membernames ~group)]
     (if (or (some #{~'account} names#) (empty? names#))
       (do ~@body)
       (friend/throw-unauthorized friend/*identity*))))