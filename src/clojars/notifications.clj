(ns clojars.notifications
  (:refer-clojure :exclude [send])
  (:require
   [clojars.db :as db]
   [clojars.event :as event]
   [clojure.string :as str]))

(defmulti notification
  (fn [type _mailer _user _data] type))

(defn send
  [mailer email title body]
  (mailer email title (str/join "\n\n" body)))

(defn handler
  [{:keys [db mailer]} type {:as data :keys [user-id username]}]
  (when (contains? (set (keys (methods notification))) type)
    (let [user (if user-id
                 (db/find-user-by-id db user-id)
                 (db/find-user db username))]
      (notification type mailer user data))))

(defn notification-component
  "Handles email notifications. Needs :db, :mailer."
  []
  (event/handler-component #'handler))
