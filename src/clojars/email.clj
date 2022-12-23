(ns clojars.email
  (:require
   [clojars.log :as log])
  (:import
   (java.util.concurrent
    CountDownLatch
    TimeUnit)
   (org.apache.commons.mail
    SimpleEmail)))

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

(let [email-latch (atom nil)]
  (defn expect-mock-emails
    "Sets up the email mock to wait for `n` emails in `wait-for-mock-emails`."
    ([]
     (expect-mock-emails 1))
    ([n]
     (reset! mock-emails [])
     (reset! email-latch (CountDownLatch. n))))

  (defn wait-for-mock-emails
    "Blocks for up to 100ms waiting for `n` emails to be sent via the mock,
  where `n` was passed to `expect-mock-emails` (defaulting to 1 if not called).
  Returns true if `n` reached within that time. Reset with `expect-mock-emails`
  between tests using the same system."
    []
    (.await @email-latch 100 TimeUnit/MILLISECONDS))

  (defn mock-mailer []
    (expect-mock-emails)
    (fn [to subject message]
      (swap! mock-emails conj [to subject message])
      (.countDown @email-latch))))
