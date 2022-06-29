(ns clojars.notifications.group
  (:require
   [clojars.notifications :as notifications]
   [clojars.notifications.common :as common]))

(defmethod notifications/notification :group-member-added
  [_type mailer
   {:as _user username :user}
   {:as data :keys [admin? group member admin-emails member-email]}]
  (let [subject (format "A%s member was added to the group %s"
                        (if admin? "n admin" "")
                        group)
        body [(format
               "User '%s' was added%s to the %s group by %s."
               member
               (if admin? " as an admin" "")
               group
               username)
              (common/details-table data)]
        emails (set (concat admin-emails [member-email]))]
    (doseq [email emails]
      (notifications/send mailer email subject body))))

(defmethod notifications/notification :group-member-removed
  [_type mailer
   {:as _user username :user}
   {:as data :keys [group member admin-emails member-email]}]
  (let [subject (format "A member was removed from the group %s"
                        group)
        body [(format
               "User '%s' was removed from the %s group by %s."
               member
               group
               username)
              (common/details-table data)]
        emails (set (concat admin-emails [member-email]))]
    (doseq [email emails]
      (notifications/send mailer email subject body))))
