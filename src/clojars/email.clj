(ns clojars.email
  (:require
   [clojars.log :as log]
   [clojure.edn :as edn]
   [clojure.java.io :as io])
  (:import
   (java.util.concurrent
    CountDownLatch
    TimeUnit)
   (org.apache.commons.mail
    SimpleEmail)))

(set! *warn-on-reflection* true)

(def ^:private email-denylist
  (edn/read-string (slurp (io/resource "email-denylist.edn"))))

(defn simple-mailer [{:keys [hostname username password port tls? from]}]
  (fn [^String to subject message]
    (log/with-context {:tag :email
                       :email-to to
                       :email-subject subject}
      (try
        (if (contains? email-denylist to)
          (log/info {:status :denylist})
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
            (log/info {:status :success})))
        (catch Exception e
          (log/error {:status :failed
                      :error e})
          (throw e))))))

(def mock-emails (atom []))

(let [email-latch (atom nil)]
  (defn expect-mock-emails
    "Sets up the email mock to wait for `n` emails in `wait-for-mock-emails`."
    ([]
     (expect-mock-emails 1))
    ([n]
     (reset! mock-emails [])
     (reset! email-latch (CountDownLatch. n))))

  (defn wait-for-mock-emails
    "Blocks for up to `wait-ms` (default: 1000ms) waiting for `n` emails to be sent
  via the mock, where `n` was passed to `expect-mock-emails` (defaulting to 1 if
  not called). Returns true if `n` reached within that time. Reset with
  `expect-mock-emails` between tests using the same system."
    ([]
     (wait-for-mock-emails 1000))
    ([wait-ms]
     (.await ^CountDownLatch @email-latch wait-ms TimeUnit/MILLISECONDS)))

  (defn mock-mailer []
    (expect-mock-emails)
    (fn [to subject message]
      (swap! mock-emails conj [to subject message])
      (.countDown ^CountDownLatch @email-latch))))
