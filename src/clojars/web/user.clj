(ns clojars.web.user
  (:use [clojure.string :only [blank?]]
        clojars.db
        clojars.web.common
        hiccup.core
        hiccup.page-helpers
        hiccup.form-helpers
        ring.middleware.session.store
        ring.util.response))

(defn register-form [ & [errors email user ssh-key]]
  (html-doc nil "Register"
            [:h1 "Register"]
            (error-list errors)
            (form-to [:post "/register"]
                     (label :email "Email:")
                     [:input {:type :email :name :email :id
                              :email :value email}]
                     (label :user "Username:")
                     (text-field :user user)
                     (label :password "Password:")
                     (password-field :password)
                     (label :confirm "Confirm password:")
                     (password-field :confirm)
                     (label :ssh-key "SSH public key:")
                     " (" (link-to
                           "http://wiki.github.com/ato/clojars-web/ssh-keys"
                           "what's this?") ")"
                           (text-area :ssh-key ssh-key)
                           (submit-button "Register"))))

(defn conj-when [coll test x]
  (if test
    (conj coll x)
    coll))

(defn valid-ssh-key? [key]
  (re-matches #"(ssh-\w+ \S+|\d+ \d+ \D+).*\s*" key))

(defn validate-profile
  "Validates a profile, returning nil if it's okay, otherwise a list
  of errors."
  [account email user password confirm ssh-key]
  (-> nil
      (conj-when (blank? email) "Email can't be blank")
      (conj-when (blank? user) "Username can't be blank")
      (conj-when (blank? password) "Password can't be blank")
      (conj-when (not= password confirm)
                 "Password and confirm password must match")
      (conj-when (or (*reserved-names* user)  ; "I told them we already
                     (and (not= account user) ; got one!"
                          (find-user user))
                     (seq (group-members user)))
                 "Username is already taken")
      (conj-when (not (re-matches #"[a-z0-9_-]+" user))
                 (str "Usernames must consist only of lowercase "
                      "letters, numbers, hyphens and underscores."))
      (conj-when (not (or (blank? ssh-key)
                          (valid-ssh-key? ssh-key)))
                 "Invalid SSH public key")))

(defn register [{email "email", user "user", password "password"
                 confirm "confirm", ssh-key "ssh-key"}]
  (if-let [errors (validate-profile nil email user password confirm ssh-key)]
    (register-form errors email user ssh-key)
    (do (add-user email user password ssh-key)
        (let [response (redirect "/")]
          (assoc-in response [:session :account] (:user user))))))

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
                       (submit-button "Update")))))

(defn update-profile [account {email :email, password :password
                               confirm :confirm, ssh-key :ssh-key}]
  (if-let [errors (validate-profile account email
                                    account password confirm ssh-key)]
    (profile-form account errors)
    (do (update-user account email account password ssh-key)
        [(redirect "/profile")])))

(defn show-user [account user]
  (html-doc account (h (user :user))
    [:h1 (h (user :user))]
    [:h2 "Jars"]
    (unordered-list (map jar-link (jars-by-user (user :user))))
    [:h2 "Groups"]
    (unordered-list (map group-link (find-groups (user :user))))))
