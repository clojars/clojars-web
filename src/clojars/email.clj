(ns clojars.email
  (:import [org.apache.commons.mail SimpleEmail]))

(defn simple-mailer [{:keys [hostname username password port tls? from]}]
  (fn [to subject message]
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
      (.send mail))))
