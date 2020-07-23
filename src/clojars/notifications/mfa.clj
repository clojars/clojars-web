(ns clojars.notifications.mfa
  (:require
   [clojars.notifications :as notifications]))

(defmethod notifications/notification :mfa-activated
  [_type mailer {:as _user username :user email :email} _data]
  (notifications/send
   mailer email "Two-factor auth was enabled on your Clojars account"
   [(format
     "Someone (hopefully you) has enabled two-factor authenication on your '%s' Clojars account."
     username)
    "If you *didn't* take this action, please reply to this email to let the Clojars admins know that your account has potentially been compromised!"
    "To manage your two-factor settings, visit https://clojars.org/mfa"]))

(defmethod notifications/notification :mfa-deactivated
  [_type mailer
   {:as _user username :user email :email}
   {:as _data :keys [source]}]
  (notifications/send
   mailer email "Two-factor auth was disabled on your Clojars account"
   [(format
     "Someone (hopefully you) has disabled two-factor authenication on your '%s' Clojars account."
     username)
    (if (= :recovery-code source)
      "Your two-factor auth was disabled because you used your recovery code."
      "Your two-factor auth was manually disabled at https://clojars.org/mfa.")
    "If you *didn't* take this action, please reply to this email to let the Clojars admins know that your account has potentially been compromised!"
    "To manage your two-factor settings, visit https://clojars.org/mfa"]))
