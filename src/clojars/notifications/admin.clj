(ns clojars.notifications.admin
  (:require
   [clojars.notifications :as notifications]
   [clojure.pprint :as pprint]))

(defmethod notifications/notification :group-verification-requested
  [_type
   {:keys [mailer] {username :user} :user}
   {:as data :keys [error]}]
  (let [result (if error "failed" "succeeded")
        subject (format "[Clojars] Group verification attempt %s" result)
        body [(format
               "User '%s' attempted to verify a group: %s"
               username
               (with-out-str (pprint/pprint data)))]]
    (notifications/send mailer "contact@clojars.org" subject body)))
