(ns clojars.notifications.user
  (:require
   [clojars.config :as config]
   [clojars.models.user :as user-m]
   [clojars.notifications :as notifications]
   [clojars.notifications.common :as common]))

(defmethod notifications/notification :mfa-activated
  [_type {:keys [mailer] {email :email username :user} :user} data]
  (notifications/send
   mailer email "Two-factor auth was enabled on your Clojars account"
   [(format
     "Someone (hopefully you) has enabled two-factor authentication on your '%s' Clojars account."
     username)
    (common/details-table data)
    common/did-not-take-action
    "To manage your two-factor settings, visit https://clojars.org/mfa"
    (common/account-footer username)]))

(defmethod notifications/notification :mfa-deactivated
  [_type
   {:keys [mailer] {email :email username :user} :user}
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
    "To manage your two-factor settings, visit https://clojars.org/mfa"
    (common/account-footer username)]))

(defmethod notifications/notification :email-changed
  [_type
   {:keys [mailer] {email :email username :user} :user}
   {:as data :keys [old-email]}]
  (let [subject "Your Clojars email was changed"
        msg
        [(format
          "Someone (hopefully you) has changed the email on your '%s' Clojars account from '%s' to '%s'."
          username
          old-email
          email)
         (common/details-table data)
         common/did-not-take-action
         (common/account-footer username)]]
    (notifications/send mailer email subject msg)
    (notifications/send mailer old-email subject msg)))

(defmethod notifications/notification :password-changed
  [_type {:keys [mailer] {email :email username :user} :user} data]
  (notifications/send
   mailer email "Your Clojars password was changed"
   [(format
     "Someone (hopefully you) has changed the password on your '%s' Clojars account."
     username)
    (common/details-table data)
    common/did-not-take-action
    (common/account-footer username)]))

(defmethod notifications/notification :password-pwned
  [_type {:keys [mailer] {email :email username :user} :user} data]
  (notifications/send
   mailer email "Your Clojars password has been disabled"
   [(format
     "Someone (hopefully you) just tried to log in to your '%s' Clojars account with a password that has appeared in a public data breach, according to Have I Been Pwned (https://haveibeenpwned.com/Passwords)."
     username)
    (common/details-table data)
    "To protect your account, we have disabled that password and logged out all of your sessions. Your deploy tokens have not been changed."
    "To log in again, set a new password at https://clojars.org/forgot-password. If you used this password anywhere else, you should change it there too."
    (common/account-footer username)]))

(defn- send-email-verification-email
  [{:keys [db mailer]} {:as user username :user} context]
  (let [code (user-m/set-email-verification-code db username)
        base-url (:base-url (config/config))
        verification-url (str base-url "/email-verification/" code)]
    (notifications/send
     mailer (:email user)
     "Confirm your Clojars email address"
     ["Hello,"
      (format "Please confirm your email address for your Clojars account: %s." username)
      "To verify your email address, click the following link:"
      verification-url
      "This link is valid for 24 hours or until you request a new confirmation email."
      (when (= :login context)
        "NOTE: After 2027-01-01, your email must be verified before you can log in.")
      (common/account-footer username)])))

(defmethod notifications/notification :user-logged-in
  [_type {:as ctx user :user} _data]
  (when (user-m/verify-email-send-allowed? user)
    (send-email-verification-email ctx user :login)))

(defmethod notifications/notification :user-registered
  [_type {:as ctx :keys [user]} _data]
  (send-email-verification-email ctx user :registration))

(defmethod notifications/notification :email-verification-email-requested
  [_type {:as ctx :keys [user]} _data]
  (when (user-m/verify-email-send-allowed? user)
    (send-email-verification-email ctx user :resend)))
