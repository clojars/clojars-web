(ns clojars.notifications.group
  (:require
   [clojars.notifications :as notifications]
   [clojars.notifications.common :as common]
   [clojars.util :as util]))

(defmethod notifications/notification :group-permission-added
  [_type mailer
   {:as _user username :user}
   {:as data :keys [admin? admin-users group member-user scope-to-jar]}]
  (let [subject (format "A%s permission was added to the group %s"
                        (if admin? "n admin" "")
                        group)
        body [(format
               "User '%s' was added%s to the %s group with scope %s by %s."
               (:user member-user)
               (if admin? " as an admin" "")
               group
               (if (nil? scope-to-jar)
                 ":all-jars"
                 (format "'%s'" scope-to-jar))
               username)
              (common/details-table data)]
        notify-users (util/distinct-by :user (conj admin-users member-user))]
    (doseq [{email :email username :user} notify-users]
      (notifications/send mailer email subject (conj body (common/account-footer username))))))

(defmethod notifications/notification :group-permission-removed
  [_type mailer
   {:as _user username :user}
   {:as data :keys [admin-users group member-user scope-to-jar]}]
  (let [subject (format "A permission was removed from the group %s"
                        group)
        body [(format
               "User '%s' was removed from the %s group with scope %s by %s."
               (:user member-user)
               group
               (if (nil? scope-to-jar)
                 ":all-jars"
                 (format "'%s'" scope-to-jar))
               username)
              (common/details-table data)]
        notify-users (util/distinct-by :user (conj admin-users member-user))]
    (doseq [{email :email username :user} notify-users]
      (notifications/send mailer email subject (conj body (common/account-footer username))))))
