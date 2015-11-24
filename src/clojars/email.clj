(ns clojars.email
  (:import [org.apache.commons.mail SimpleEmail]))

(defn simple-mailer [{:keys [hostname username password port ssl from] :as config}]
  (fn [to subject message]
    (let [mail (doto (SimpleEmail.)
                 (.setHostName (or hostname "localhost"))
                 (.setSslSmtpPort (str (or port 25)))
                 (.setSmtpPort (or port 25))
                 (.setSSL (or ssl false))
                 (.setFrom (or from "noreply@clojars.org") "Clojars")
                 (.addTo to)
                 (.setSubject subject)
                 (.setMsg message))]
      (when (and username password)
        (.setAuthentication mail username password))
      (.send mail))))
