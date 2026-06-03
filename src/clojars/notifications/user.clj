(ns clojars.notifications.user
  (:require
   [clojars.notifications :as notifications]
   [clojars.notifications.common :as common]))

(defmethod notifications/notification :mfa-activated
  [_type mailer {:as _user username :user email :email} data]
  (notifications/send
   mailer email "Two-factor auth was enabled on your Clojars account"
   [(format
     "Someone (hopefully you) has enabled two-factor authentication on your '%s' Clojars account."
     username)
    (common/details-table data)
    common/did-not-take-action
    "To manage your two-factor settings, visit https://clojars.org/mfa"]))

(defmethod notifications/notification :mfa-deactivated
  [_type mailer
   {:as _user username :user email :email}
   {:as data :keys [source]}]
  (notifications/send
   mailer email "Two-factor auth was disabled on your Clojars account"
   [(format
     "Someone (hopefully you) has disabled two-factor authentication on your '%s' Clojars account."
     username)
    (if (= :recovery-code source)
      "Your two-factor auth was disabled because you used your recovery code."
      "Your two-factor auth was manually disabled at https://clojars.org/mfa.")
    (common/details-table data)
    common/did-not-take-action
    "To manage your two-factor settings, visit https://clojars.org/mfa"]))

(defmethod notifications/notification :email-changed
  [_type mailer {:as _user username :user email :email}
   {:as data :keys [old-email]}]
  (let [subject "Your Clojars email was changed"
        msg
        [(format
          "Someone (hopefully you) has changed the email on your '%s' Clojars account from '%s' to '%s'."
          username
          old-email
          email)
         (common/details-table data)
         common/did-not-take-action]]
    (notifications/send mailer email subject msg)
    (notifications/send mailer old-email subject msg)))

(defmethod notifications/notification :password-changed
  [_type mailer {:as _user username :user email :email} data]
  (notifications/send
   mailer email "Your Clojars password was changed"
   [(format
     "Someone (hopefully you) has changed the password on your '%s' Clojars account."
     username)
    (common/details-table data)
    common/did-not-take-action]))

(defmethod notifications/notification :password-compromised
  [_type mailer {:as _user username :user email :email} _data]
  (notifications/send
   mailer email "Your Clojars password has been reset"
   [(format
     "We detected that the password on your '%s' Clojars account has appeared in a known public data breach (see https://haveibeenpwned.com/Passwords)."
     username)
    "To protect your account, we have cleared the password. You will need to choose a new one before you can log in again."
    "To set a new password, visit https://clojars.org/forgot-password and follow the instructions in the email we send you."
    "We did not log you in. The login attempt that triggered this email used a known-compromised password, but we cannot tell whether it was you or someone using a leaked password. If it was you, please pick a new, unique password. If it wasn't, this email is your signal that the password should not be used anywhere."]))
