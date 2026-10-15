(ns clojars.notifications
  (:refer-clojure :exclude [send])
  (:require
   [clojars.event :as event]
   [clojars.models.user :as user-m]
   [clojure.string :as str]))

(defmulti notification
  (fn [type _ctx _data] type))

(defn send
  [mailer email title body]
  (mailer email title (str/join "\n\n" (remove nil? body))))

(defn handler
  [{:keys [db mailer]} type {:as data :keys [user user-id username]}]
  (when (contains? (set (keys (methods notification))) type)
    (let [user' (cond
                  user     user
                  user-id  (user-m/find-user-by-id db user-id)
                  username (user-m/find-user db username)
                  :else    nil)]
      (notification type
                    {:db db
                     :mailer mailer
                     :user user'}
                    data))))

(defn notification-component
  "Handles email notifications. Needs :db, :mailer."
  []
  (event/handler-component #'handler))


