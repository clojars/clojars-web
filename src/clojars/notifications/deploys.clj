(ns clojars.notifications.deploys
  (:require
   [clojars.notifications :as notifications]))

(defn- version-url
  [group name version]
  (format "https://clojars.org/%s/%s/versions/%s"
          group name version))

(defmethod notifications/notification :version-deployed
  [_type mailer
   {:as _user email :email send-email? :send_deploy_emails}
   {:keys [deployer-username group name version]}]
  (when send-email?
    (let [ga (format "%s/%s" group name)]
      (notifications/send
       mailer email (format "[Clojars] %s deployed %s %s" deployer-username ga version)
       [(format
         "User '%s' just deployed %s %s to Clojars: %s"
         deployer-username ga version (version-url group name version))
        "If you believe this is malicious activity, please reply to this email immediately and let the Clojars staff know!"
        "You can turn off these notifications in your settings on https://clojars.org."]))))
