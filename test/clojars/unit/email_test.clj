(ns clojars.unit.email-test
  (:require [bote.core :refer [create-smtp-server]]
            [clojars.email :as email]
            [clojure.test :refer [deftest is]])
  (:import javax.net.ssl.SSLException
           org.apache.commons.mail.EmailException
           [org.subethamail.smtp.auth EasyAuthenticationHandlerFactory LoginFailedException UsernamePasswordValidator]))

(deftest simple-mailer-sends-emails
  (let [transport (promise)
        server (create-smtp-server #(deliver transport %) :port 0)]
    (try
      (.start server)
      ((email/simple-mailer {:host "localhost"
                             :port (.getPort server)
                             :from "example@example.org"})
       "to@example.org"
       "the subject"
       "A message")
      (let [email (deref transport 100 nil)]
        (is email)
        (is (= "A message" (.trim (:content email))))
        (is (= "the subject" (:subject email)))
        (is (= "to@example.org" (:to email)))
        (is (= "example@example.org" (:from email))))
      (finally (.stop server)))))

(deftest simple-mailer-sends-username-and-password
  (let [transport (promise)
        server (create-smtp-server #(deliver transport %) :port 0)]
    (try
      (.setAuthenticationHandlerFactory
       server
       (EasyAuthenticationHandlerFactory.
        (reify UsernamePasswordValidator
          (login [t username password]
            (when (or (not= username "username")
                      (not= password "password"))
              (throw (LoginFailedException.)))))))
      (.start server)
      ((email/simple-mailer {:host "localhost"
                             :port (.getPort server)
                             :from "example@example.org"
                             :username "username"
                             :password "password"})
       "to@example.org"
       "the subject"
       "A message")
      (is (deref transport 100 nil))
      (finally (.stop server)))))


(deftest simple-mailer-uses-tls
  (let [transport (promise)
        server (create-smtp-server #(deliver transport %)
                                   :port 0)]
    (try
      (.start server)
      ;;TODO actually setting up an ssl server
      ;;and checking the message would be better
      ;;but it looks like the tls stuff is a mess
      ;;this sufficies to say it tried
      ((email/simple-mailer {:host "localhost"
                             :port (.getPort server)
                             :from "example@example.org"
                             :ssl true})
            "to@example.org"
            "the subject"
       "A message")
      (is false)
      (catch EmailException e
        ;;traverse to the root cause
        (let [e (.getCause (.getCause e))]
          (is (instance? SSLException e))))
      (finally (.stop server)))))
