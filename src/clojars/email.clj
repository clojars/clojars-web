(ns clojars.email
  (:import [org.apache.commons.mail SimpleEmail])
  (:require [clojars.log :as log]))

(defn simple-mailer [{:keys [hostname username password port tls? from]}]
  (fn [to subject message]
    (log/with-context {:tag :email
                       :email-to to
                       :email-subject subject}
      (try
        (let [mail (doto (SimpleEmail.)
                     (.setHostName (or hostname "localhost"))
                     (.setSmtpPort (or port 25))
                     (.setStartTLSEnabled (boolean tls?))
                     (.setStartTLSRequired (boolean tls?))
                     (.setFrom (or from "contact@clojars.org") "Clojars")
                     (.addTo to)
                     (.setSubject subject)
                     (.setMsg message))]
          (when tls?
            (.setSslSmtpPort mail (str (or port 25))))
          (when (and username password)
            (.setAuthentication mail username password))
          (.send mail)
          (log/info {:status :success}))
        (catch Exception e
          (log/error {:status :failed
                      :error e})
          (throw e))))))

(def mock-emails (atom []))

(defn mock-mailer []
  (reset! mock-emails [])
  (fn [to subject message]
    (swap! mock-emails conj [to subject message])))
