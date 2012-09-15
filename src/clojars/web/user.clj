(ns clojars.web.user
  (:require [clojars.config :as config]
            [clojars.db :refer [find-user group-membernames add-user
                                reserved-names update-user jars-by-username
                                find-groupnames find-user-by-user-or-email
                                rand-string split-keys]]
            [clojars.web.common :refer [html-doc error-list jar-link
                                        group-link]]
            [clojure.string :refer [blank?]]
            [hiccup.core :refer [h]]
            [hiccup.element :refer [link-to unordered-list]]
            [hiccup.form :refer [label text-field
                                 password-field text-area
                                 submit-button]]
            [clojars.web.safe-hiccup :refer [form-to]]
            [ring.util.response :refer [response redirect]])
  (:import [org.apache.commons.mail SimpleEmail]))

(defn register-form [ & [errors email username ssh-key]]
  (html-doc nil "Register"
            [:h1 "Register"]
            (error-list errors)
            (form-to [:post "/register"]
                     (label :email "Email:")
                     [:input {:type :email :name :email :id
                              :email :value email}]
                     (label :username "Username:")
                     (text-field :username username)
                     (label :password "Password:")
                     (password-field :password)
                     (label :confirm "Confirm password:")
                     (password-field :confirm)
                     (label :ssh-key "SSH public key:")
                     " (" (link-to
                           "http://wiki.github.com/ato/clojars-web/ssh-keys"
                           "what's this?") ")"
                           (text-area :ssh-key ssh-key)
                           [:p.hint "Entering multiple keys? Put them on separate lines."]
                           (submit-button "Register"))))

(defn conj-when [coll test x]
  (if test
    (conj coll x)
    coll))

(defn valid-ssh-key? [key]
  (every? #(re-matches #"(ssh-\w+ \S+|\d+ \d+ \D+).*\s*" %) (split-keys key)))

(defn validate-profile
  "Validates a profile, returning nil if it's okay, otherwise a list
  of errors."
  [account email username password confirm ssh-key]
  (-> nil
      (conj-when (blank? email) "Email can't be blank")
      (conj-when (blank? username) "Username can't be blank")
      (conj-when (blank? password) "Password can't be blank")
      (conj-when (not= password confirm)
                 "Password and confirm password must match")
      (conj-when (and (nil? account) ; only check username on register
                      (or (reserved-names username)  ; "I told them we already
                          (and (not= account username) ; got one!"
                               (find-user username))
                          (seq (group-membernames username))))
                 "Username is already taken")
      (conj-when (not (re-matches #"[a-z0-9_-]+" username))
                 (str "Username must consist only of lowercase "
                      "letters, numbers, hyphens and underscores."))
      (conj-when (not (or (blank? ssh-key)
                          (valid-ssh-key? ssh-key)))
                 "Invalid SSH public key")))

(defn profile-form [account & [errors]]
  (let [user (find-user account)]
    (html-doc account "Profile"
              [:h1 "Profile"]
              (error-list errors)
              (form-to [:post "/profile"]
                       (label :email "Email:")
                       [:input {:type :email :name :email :id
                                :email :value (user :email)}]
                       (label :password "Password:")
                       (password-field :password)
                       (label :confirm "Confirm password:")
                       (password-field :confirm)
                       (label :ssh-key "SSH public key:")
                       (text-area :ssh-key (user :ssh_key))
                       [:p.hint "Entering multiple keys? Put them on separate lines."]
                       (submit-button "Update")))))

(defn update-profile [account {:keys [email password confirm ssh-key]}]
  (if-let [errors (validate-profile account email
                                    account password confirm ssh-key)]
    (profile-form account errors)
    (do (update-user account email account password ssh-key)
        (redirect "/profile"))))

(defn show-user [account user]
  (html-doc account (h (user :user))
    [:h1 (h (user :user))]
    [:h2 "Jars"]
    (unordered-list (map jar-link (jars-by-username (user :user))))
    [:h2 "Groups"]
    (unordered-list (map group-link (find-groupnames (user :user))))))

(defn forgot-password-form []
  (html-doc nil "Forgot password?"
    [:h1 "Forgot password?"]
    (form-to [:post "/forgot-password"]
      (label :email-or-username "Email or username:")
      (text-field :email-or-username)
      (submit-button "Send new password"))))

(defn ^{:dynamic true} send-out [email]
  (.send email))

;; TODO: move this to another file?
(defn send-mail [to subject message]
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

(defn forgot-password [{:keys [email-or-username]}]
  (when-let [user (find-user-by-user-or-email email-or-username)]
    (let [new-password (rand-string 15)]
      (update-user (user :user) (user :email) (user :user) new-password (user :ssh_key))
      (send-mail (user :email)
        "Password reset for Clojars"
        (str "Hello,\n\nYour new password for Clojars is: " new-password "\n\nKeep it safe this time."))))
  (html-doc nil "Forgot password?"
    [:h1 "Forgot password?"]
    [:p "If your account was found, you should get an email with a new password soon."]))
