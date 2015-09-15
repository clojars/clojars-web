(ns clojars.web.user
  (:require [clojars.db :refer [find-user group-membernames add-user
                                reserved-names update-user jars-by-username
                                find-groupnames find-user-by-user-or-email
                                rand-string split-keys]]
            [clojars.web.common :refer [html-doc error-list jar-link
                                        flash group-link]]
            [clojure.string :refer [blank?]]
            [hiccup.element :refer [link-to unordered-list]]
            [hiccup.form :refer [label text-field
                                 password-field text-area
                                 submit-button
                                 email-field]]
            [clojars.web.safe-hiccup :refer [form-to]]
            [clojars.email :as email]
            [ring.util.response :refer [response redirect]]
            [valip.core :refer [validate]]
            [valip.predicates :as pred]))

(defn register-form [ & [errors email username ssh-key pgp-key]]
  (html-doc nil "Register"
            [:div.small-section
             [:h1 "Register"]
             (error-list errors)
             (form-to [:post "/register"]
                      (label :email "Email")
                      (email-field {:value email
                                    :required true
                                    :placeholder "bob@example.com"}
                                   :email)
                      (label :username "Username")
                      (text-field {:required true
                                   :placeholder "bob"}
                                  :username)
                      (label :password "Password")
                      (password-field {:placeholder "keep it secret, keep it safe"
                                       :required true}
                                      :password)
                      (label :confirm "Confirm password")
                      (password-field {:placeholder "confirm your password"
                                       :required true}
                                      :confirm)
                      ;; put this in a div so we can hide it - we
                      ;; currently rely on being able to set an
                      ;; ssh-key for integ tests
                      [:div#ssh-key
                       (label :ssh-key "SSH public key")
                       ;; [:p.hint
                       ;;  " (" (link-to
                       ;;        "http://wiki.github.com/ato/clojars-web/ssh-keys"
                       ;;        "what's this?") ")"]
                       (text-area :ssh-key ssh-key)
                       ;; [:p.hint "Entering multiple SSH keys? Put
                       ;; them on separate lines."]
                       ]
                      (label :pgp-key "PGP public key")
                      [:p.hint "Optional - needed only if you sign releases"]
                      (text-area :pgp-key pgp-key)
                      (submit-button "Register"))]))

(defn conj-when [coll test x]
  (if test
    (conj coll x)
    coll))

(defn valid-pgp-key? [key]
  (and (.startsWith key "-----BEGIN PGP PUBLIC KEY BLOCK-----")
       (.endsWith key "-----END PGP PUBLIC KEY BLOCK-----")))

(defn valid-ssh-key? [key]
  (every? #(re-matches #"(ssh-\w+ \S+|\d+ \d+ \D+).*\s*" %) (split-keys key)))

(defn update-user-validations [confirm]
  [[:email pred/present? "Email can't be blank"]
   [:username #(re-matches #"[a-z0-9_-]+" %)
    (str "Username must consist only of lowercase "
         "letters, numbers, hyphens and underscores.")]
   [:username pred/present? "Username can't be blank"]
   [:password #(= % confirm) "Password and confirm password must match"]
   [:ssh-key #(or (blank? %) (valid-ssh-key? %))
    "Invalid SSH public key"]
   [:pgp-key #(or (blank? %) (valid-pgp-key? %))
    "Invalid PGP public key"]])

(defn new-user-validations [confirm]
  (concat [[:password pred/present? "Password can't be blank"]
           [:username #(not (or (reserved-names %)
                                (find-user %)
                                (seq (group-membernames %))))
            "Username is already taken"]]
          (update-user-validations confirm)))

(defn profile-form [account flash-msg & [errors]]
  (let [user (find-user account)]
    (html-doc account "Profile"
              [:div.small-section
               (flash flash-msg)
               [:h1 "Profile"]
               (error-list errors)
               (form-to [:post "/profile"]
                        (label :email "Email")
                        [:input {:type :email :name :email :id
                                 :email :value (user :email)}]
                        (label :password "Password")
                        (password-field :password)
                        (label :confirm "Confirm password")
                        (password-field :confirm)
                        ;; (label :ssh-key "SSH public key")
                        ;; (text-area :ssh-key (user :ssh_key))
                        ;; [:p.hint "Entering multiple SSH keys? Put them on separate lines."]
                        (label :pgp-key "PGP public key")
                        [:p.hint "Optional - needed only if you sign releases"]
                        (text-area :pgp-key (user :pgp_key))
                        (submit-button "Update"))])))

(defn update-profile [account {:keys [email password confirm ssh-key pgp-key]}]
  (let [pgp-key (and pgp-key (.trim pgp-key))]
    (if-let [errors (apply validate {:email email
                                     :username account
                                     :password password
                                     ;; :ssh-key ssh-key
                                     :pgp-key pgp-key}
                           (update-user-validations confirm))]
      (profile-form account nil (apply concat (vals errors)))
      (do (update-user account email account password (or ssh-key "") pgp-key)
          (assoc (redirect "/profile")
            :flash "Profile updated.")))))

(defn show-user [account user]
  (html-doc account (user :user)
            [:div.light-article.row
             [:h1.col-md-12.col-sm-12.col-xs-12.col-lg-12
              (user :user)]
             [:div.col-sm-6.col-lg-6.col-xs-12.col-md-6
              [:h2 "Projects"]
              (unordered-list (map jar-link (jars-by-username (user :user))))]
             [:div.col-sm-6.col-lg-6.col-xs-12.col-md-6
              [:h2 "Groups"]
              (unordered-list (map group-link (find-groupnames (user :user))))]]))

(defn forgot-password-form []
  (html-doc nil "Forgot password?"
    [:div.small-section
     [:h1 "Forgot password?"]
     (form-to [:post "/forgot-password"]
              (label :email-or-username "Email or Username")
              (text-field {:placeholder "bob"
                           :required true}
                          :email-or-username)
              (submit-button "Send new password"))]))


(defn forgot-password [{:keys [email-or-username]}]
  (when-let [user (find-user-by-user-or-email email-or-username)]
    (let [new-password (rand-string 15)]
      (update-user (user :user) (user :email) (user :user) new-password
                   (user :ssh_key) (user :pgp_key))
      (email/send-email (user :email)
        "Password reset for Clojars"
        (str "Hello,\n\nYour new password for Clojars is: " new-password "\n\nKeep it safe this time."))))
  (html-doc nil "Forgot password?"
    [:h1 "Forgot password?"]
    [:p "If your account was found, you should get an email with a new password soon."]))
