(ns clojars.notifications.token
  (:require
   [clojars.notifications :as notifications]
   [clojars.notifications.common :as common]))

(defn- scope-description [{:keys [group-name jar-name]}]
  (cond
    jar-name   (format "%s/%s" group-name jar-name)
    group-name (format "%s/* (any artifact in group you have access to)" group-name)
    :else      "* (any group/artifact you have access to)"))

(defmethod notifications/notification :deploy-token-created
  [_type
   {:keys [mailer] {email :email username :user} :user}
   {:as event :keys [token-name single-use? expires-at]}]
  (notifications/send
   mailer email "A deploy token was created on your Clojars account"
   ["Hello,"
    (format
     "Someone (hopefully you) has created a new deploy token named '%s' on your '%s' Clojars account."
     token-name
     username)
    (format "Scope: %s\nSingle use: %s\nExpires: %s"
            (scope-description event)
            (if single-use? "yes" "no")
            (if expires-at (str expires-at) "never"))
    (common/details-table event)
    common/did-not-take-action
    "To manage your deploy tokens, visit https://clojars.org/tokens"
    (common/account-footer username)]))

(defmethod notifications/notification :token-breached
  [_type
   {:keys [mailer] {email :email username :user} :user}
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
      "This token has been disabled to prevent malicious use.")
    (common/account-footer username)]))

(defmethod notifications/notification :token-found-in-deploy
  [_type
   {:keys [mailer] {email :email username :user} :user}
   {:keys [token-disabled? token-name location group artifact version]}]
  (notifications/send
   mailer email "Deploy token found in a deployment"
   ["Hello,"
    (format
     "Your deploy token named '%s' was found in %s, which was being deployed to Clojars as %s/%s %s. We rejected the deployment, so the token was not published."
     token-name location group artifact version)
    (if token-disabled?
      "The token was already disabled, so we took no further action."
      "This token has been disabled to prevent malicious use.")
    "If the file is in source control or was shared anywhere else, the token may be exposed there too. Keep tokens out of build files, for example by reading them from environment variables. To create a new token, visit https://clojars.org/tokens"
    (common/account-footer username)]))
