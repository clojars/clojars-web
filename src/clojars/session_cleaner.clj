(ns clojars.session-cleaner
  (:require
   [com.stuartsierra.component :as component]
   [jdbc-ring-session.cleaner :as cleaner]))

(defrecord SessionCleaner [db]
  component/Lifecycle
  (start [this]
    (assoc this ::cleaner (cleaner/start-cleaner db)))
  (stop [this]
    (when-some [cleaner (::cleaner this)]
      (cleaner/stop-cleaner cleaner))))

(defn new-session-cleaner
  []
  (->SessionCleaner nil))
