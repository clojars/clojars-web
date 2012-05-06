(ns clojars.auth
  (:require [cemerick.friend :as friend]
            [clojars.db :refer [group-membernames]]))

(defmacro with-account [body]
  `(friend/authenticated (try-account ~body)))

(defmacro try-account [body]
  `(let [~'account (:username (friend/current-authentication))]
     ~body))

(defmacro authorized! [group & body]
  `(if (or (some #{~'account} (group-membernames ~group))
           (empty? (group-membernames ~group)))
     (do ~@body)
     (friend/throw-unauthorized friend/*identity*)))