(ns clojars.unit.notifications.token-test
  (:require
   [clojars.db :as db]
   [clojars.notifications :as notifications]
   ;; for defmethod
   [clojars.notifications.token]
   [clojars.test-helper :as help]
   [clojure.test :refer [deftest is use-fixtures]])
  (:import
   (java.sql
    Timestamp)
   (java.util
    Date)))

(use-fixtures :each
  help/default-fixture
  help/with-clean-database)

(deftest deploy-token-created-handler-sends-safe-email
  (db/add-user help/*db* "fixture@example.org" "fixture" "password1234")
  (let [sent (atom [])]
    (notifications/handler
     {:db help/*db*
      :mailer (fn [to subj msg]
                 (swap! sent conj [to subj msg]))}
     :deploy-token-created
     {:username    "fixture"
      :token-name  "my-laptop"
      :group-name  nil
      :jar-name    nil
      :single-use? false
      :expires-at  nil
      :remote-addr "127.0.0.1"
      :user-agent  "TestAgent/1"
      :timestamp   (Date.)})
    (is (= 1 (count @sent)))
    (let [[to subject body] (first @sent)]
      (is (= "fixture@example.org" to))
      (is (= "A new deploy token was created on your Clojars account" subject))
      (is (re-find #"Hello," body))
      (is (re-find #"my-laptop" body))
      (is (re-find #"Scope: \*" body))
      (is (re-find #"Single use: no" body))
      (is (re-find #"Expires: never" body))
      (is (re-find #"127.0.0.1" body))
      (is (re-find #"TestAgent/1" body))
      (is (not (re-find #"CLOJARS_" body))))))

(deftest deploy-token-created-email-includes-expiry-when-set
  (db/add-user help/*db* "fixture@example.org" "fixture" "password1234")
  (let [t (Timestamp/valueOf "2020-06-15 12:00:00")
        body-msg
        (let [out (atom nil)]
          (notifications/handler
           {:db help/*db*
            :mailer (fn [_to _subj msg]
                      (reset! out msg))}
           :deploy-token-created
           {:username    "fixture"
            :token-name  "t"
            :group-name  nil
            :jar-name    nil
            :single-use? false
            :expires-at  t
            :remote-addr "127.0.0.1"
            :user-agent  "UA"
            :timestamp   (Date.)})
          @out)
        expect (format "Expires: %s" (str t))]
    (is (re-find (re-pattern (java.util.regex.Pattern/quote expect)) body-msg))))
