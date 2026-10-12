(ns clojars.models.user
  (:require
   [clojars.db :as db]
   [clojars.time :as time]
   [next.jdbc.sql :as sql])
  (:import
   (java.sql
    Timestamp)
   (java.time
    Instant)))

(set! *warn-on-reflection* true)

;; Email verification

(def verify-email-resend-rate-limit-seconds 60)

(defn verify-email-send-allowed?
  [{:as _user
    ^Timestamp code-created-at :email_verification_code_created_at
    email-verified? :email_verified}]
  (and (not email-verified?)
       (let [now (time/now)
             last-resend (if code-created-at
                           (.toInstant code-created-at)
                           Instant/EPOCH)
             elapsed-s (time/seconds-between last-resend now)]
         (> elapsed-s verify-email-resend-rate-limit-seconds))))

(defn set-email-verification-code
  [db username]
  (let [code (db/generate-secure-hex-token 20)]
    (sql/update! db :users
                 {:email_verification_code            code
                  :email_verification_code_created_at (db/get-time)}
                 {db/user-column username})
    code))

(defn find-user-by-email-verification-code
  [db code]
  (first
   (db/q db
         {:select :*
          :from :users
          :where [:and
                  [:= :email_verification_code code]
                  [:>= :email_verification_code_created_at
                   (Timestamp/from (time/days-ago 1))]]
          :limit 1})))

(defn mark-email-verified
  [db username]
  (sql/update! db :users
               {:email_verified                     true
                :email_verified_at                  (db/get-time)
                :email_verification_code            nil
                :email_verification_code_created_at nil}
               {db/user-column username}))

(defn clear-email-verification
  [db username]
  (sql/update! db :users
               {:email_verification_code            nil
                :email_verification_code_created_at nil}
               {db/user-column username}))

(defn mark-email-unverified
  [db username]
  (sql/update! db :users
               {:email_verified                     false
                :email_verified_at                  nil
                :email_verification_code            nil
                :email_verification_code_created_at nil}
               {db/user-column username}))


