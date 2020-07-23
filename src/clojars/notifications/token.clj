(ns clojars.notifications.token
  (:require
   [clojars.notifications :as notifications]))

(defmethod notifications/notification :token-breached
  [_type mailer {:as _user :keys [email]}
   {:as _data :keys [token-disabled? token-name commit-url]}]
  (notifications/send
   mailer email "Deploy token found on GitHub"
   ["Hello,"
    (format
     "We received a notice from GitHub that your deploy token named '%s' was found by their secret scanning service."
     token-name)
    (format "The commit was found at: %s" commit-url)
    (if token-disabled?
      "The token was already disabled, so we took no further action."
      "This token has been disabled to prevent malicious use.")]))

