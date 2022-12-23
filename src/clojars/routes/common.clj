(ns clojars.routes.common
  (:import
   (java.util
    Date)))

(defn request-details
  "Captures a map of audit details from the request."
  [{:as _request :keys [headers remote-addr]}]
  (let [{:strs [user-agent x-forwarded-for]} headers]
    {:remote-addr (or x-forwarded-for remote-addr)
     :timestamp   (Date.)
     :user-agent  user-agent}))
