(ns clojars.notifications.mfa
  (:refer-clojure :exclude [send])
  (:require
   [clojars.db :as db]
   [clojars.event :as event]
   [clojure.string :as str]))

(defmulti notification
  (fn [type _mailer _user _data] type))

(defn- send
  [mailer email title body]
  (mailer email title (str/join "\n\n" body)))

(defmethod notification :mfa-activated
  [_type mailer {:as _user username :user email :email} _data]
  (send
   mailer email "Two-factor auth was enabled on your Clojars account"
   [(format
     "Someone (hopefully you) has enabled two-factor authenication on your '%s' Clojars account."
     username)
    "If you *didn't* take this action, please reply to this email to let the Clojars admins know that your account has potentially been compromised!"
    "To manage your two-factor settings, visit https://clojars.org/mfa"]))

(defmethod notification :mfa-deactivated
  [_type mailer
   {:as _user username :user email :email}
   {:as _data :keys [source]}]
  (send
   mailer email "Two-factor auth was disabled on your Clojars account"
   [(format
     "Someone (hopefully you) has disabled two-factor authenication on your '%s' Clojars account."
     username)
    (if (= :recovery-code source)
      "Your two-factor auth was disabled because you used your recovery code."
      "Your two-factor auth was manually disabled at https://clojars.org/mfa.")
    "If you *didn't* take this action, please reply to this email to let the Clojars admins know that your account has potentially been compromised!"
    "To manage your two-factor settings, visit https://clojars.org/mfa"]))

(defn handler
  [{:keys [db mailer]} type {:as data :keys [username]}]
  (when (contains? (set (keys (methods notification))) type)
    (let [user (db/find-user db username)]
      (notification type mailer user data))))

(defn notification-component
  "Handles mfa notifications. Needs :db, :mailer."
  []
  (event/handler-component #'handler))
