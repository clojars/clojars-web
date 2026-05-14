(ns clojars.notifications.token
  (:require
   [clojars.notifications :as notifications]
   [clojars.notifications.common :as common]))

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

(defn- format-scope
  [group-name jar-name]
  (cond
    (and group-name jar-name) (format "'%s/%s'" group-name jar-name)
    group-name                (format "'%s' (any jar in group)" group-name)
    :else                     "any group/jar"))

(defmethod notifications/notification :token-created
  [_type mailer {:as _user username :user email :email}
   {:as data :keys [token-name group-name jar-name single-use? expires-at]}]
  (notifications/send
   mailer email "A deploy token was created on your Clojars account"
   [(format
     "Someone (hopefully you) has created a new deploy token named '%s' on your '%s' Clojars account."
     token-name
     username)
    (format "Scope: %s" (format-scope group-name jar-name))
    (format "Single use: %s" (if single-use? "yes" "no"))
    (format "Expires: %s" (if expires-at (str expires-at) "never"))
    (common/details-table data)
    common/did-not-take-action
    "To manage your deploy tokens, visit https://clojars.org/tokens"]))

