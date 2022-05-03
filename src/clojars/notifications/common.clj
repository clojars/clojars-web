(ns clojars.notifications.common
  (:import
   (java.text SimpleDateFormat)))

(def did-not-take-action
  "If you *didn't* take this action, please reply to this email to let the Clojars admins know that your account has potentially been compromised!")

(defn- iso-8601 []
  (SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ss.SSSZ"))

(defn details-table
  [{:as _details :keys [remote-addr timestamp user-agent]}]
  ;; We don't always have the details in every context. One example is
  ;; mfa-disablement from using a recovery code - that happens in the bowels of
  ;; friend, so it is difficult to capture the rquest context there.
  (if remote-addr
    (format "Client IP: %s\nUser agent: %s\nTimestamp: %s"
            remote-addr user-agent (.format (iso-8601) timestamp))
    ""))
