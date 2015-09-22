(ns clojars.email
  (:require [clojars.config :as config])
  (:import [org.apache.commons.mail SimpleEmail]))

(defn send-out [email]
  (.send email))

(defn send-email [to subject message]
  (let [{:keys [hostname username password port ssl from]} (config/config :mail)
        mail (doto (SimpleEmail.)
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
    (send-out mail)))
