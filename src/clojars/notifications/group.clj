(ns clojars.notifications.group
  (:require
   [clojars.notifications :as notifications]
   [clojars.notifications.common :as common]))

(defmethod notifications/notification :group-permission-added
  [_type mailer
   {:as _user username :user}
   {:as data :keys [admin? group member admin-emails member-email scope-to-jar]}]
  (let [subject (format "A%s permission was added to the group %s"
                        (if admin? "n admin" "")
                        group)
        body [(format
               "User '%s' was added%s to the %s group with scope %s by %s."
               member
               (if admin? " as an admin" "")
               group
               (if (nil? scope-to-jar)
                 ":all-jars"
                 (format "'%s'" scope-to-jar))
               username)
              (common/details-table data)]
        emails (set (concat admin-emails [member-email]))]
    (doseq [email emails]
      (notifications/send mailer email subject body))))

(defmethod notifications/notification :group-permission-removed
  [_type mailer
   {:as _user username :user}
   {:as data :keys [group member admin-emails member-email scope-to-jar]}]
  (let [subject (format "A permission was removed from the group %s"
                        group)
        body [(format
               "User '%s' was removed from the %s group with scope %s by %s."
               member
               group
               (if (nil? scope-to-jar)
                 ":all-jars"
                 (format "'%s'" scope-to-jar))
               username)
              (common/details-table data)]
        emails (set (concat admin-emails [member-email]))]
    (doseq [email emails]
      (notifications/send mailer email subject body))))
