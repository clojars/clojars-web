(ns clojars.notifications.token
  (:require
   [clojars.notifications :as notifications]
   [clojars.notifications.common :as common]))

(defn- scope-description [{:keys [group_name jar_name]}]
  (cond
    jar_name   (format "%s/%s" group_name jar_name)
    group_name (format "%s/*" group_name)
    :else      "*"))

(defmethod notifications/notification :deploy-token-created
  [_type mailer {:as _user username :user email :email}
   {:as data :keys [token-name group-name jar-name single-use? expires-at]}]
  (notifications/send
   mailer email "A new deploy token was created on your Clojars account"
   ["Hello,"
    (format
     "Someone (hopefully you) has created a new deploy token named '%s' on your '%s' Clojars account."
     token-name
     username)
    (format "Scope: %s" (scope-description {:group_name group-name :jar_name jar-name}))
    (format "Single use: %s" (if single-use? "yes" "no"))
    (if expires-at
      (format "Expires: %s" (str expires-at))
      "Expires: never")
    (common/details-table data)
    common/did-not-take-action
    "To review or disable deploy tokens, visit https://clojars.org/tokens"]))

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

