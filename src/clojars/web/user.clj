(ns clojars.web.user
  (:require [clojars.db :as db :refer [find-user group-membernames add-user
                                reserved-names update-user jars-by-username
                                find-groupnames find-user-by-user-or-email
                                rand-string]]
            [clojars.web.common :refer [html-doc error-list jar-link
                                        flash group-link]]
            [clojars.config :refer [config]]
            [clojure.string :as string :refer [blank?]]
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

(defn register-form [ & [errors email username pgp-key]]
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

(defn update-user-validations [confirm]
  [[:email pred/present? "Email can't be blank"]
   [:username #(re-matches #"[a-z0-9_-]+" %)
    (str "Username must consist only of lowercase "
         "letters, numbers, hyphens and underscores.")]
   [:username pred/present? "Username can't be blank"]
   [:password #(= % confirm) "Password and confirm password must match"]
   [:pgp-key #(or (blank? %) (valid-pgp-key? %))
    "Invalid PGP public key"]])

(defn new-user-validations [db confirm]
  (concat [[:password pred/present? "Password can't be blank"]
           [:username #(not (or (reserved-names %)
                                (find-user db %)
                                (seq (group-membernames db %))))
            "Username is already taken"]]
          (update-user-validations confirm)))

(defn profile-form [account user flash-msg & [errors]]
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
                      (label :pgp-key "PGP public key")
                      [:p.hint "Optional - needed only if you sign releases"]
                      (text-area :pgp-key (user :pgp_key))
                      (submit-button "Update"))]))

(defn update-profile [db account {:keys [email password confirm pgp-key] :as params}]
  (let [pgp-key (and pgp-key (.trim pgp-key))]
    (if-let [errors (apply validate {:email email
                                     :username account
                                     :password password
                                     :pgp-key pgp-key}
                           (update-user-validations confirm))]
      (profile-form account params nil (apply concat (vals errors)))
      (do (update-user db account email account password pgp-key)
          (assoc (redirect "/profile")
            :flash "Profile updated.")))))

(defn show-user [db account user]
  (html-doc account (user :user)
            [:div.light-article.row
             [:h1.col-md-12.col-sm-12.col-xs-12.col-lg-12
              (user :user)]
             [:div.col-sm-6.col-lg-6.col-xs-12.col-md-6
              [:h2 "Projects"]
              (unordered-list (map jar-link (jars-by-username db (user :user))))]
             [:div.col-sm-6.col-lg-6.col-xs-12.col-md-6
              [:h2 "Groups"]
              (unordered-list (map group-link (find-groupnames db (user :user))))]]))

(defn forgot-password-form []
  (html-doc nil "Forgot password?"
    [:div.small-section
     [:h1 "Forgot password?"]
     (form-to [:post "/forgot-password"]
              (label :email-or-username "Email or Username")
              (text-field {:placeholder "bob"
                           :required true}
                          :email-or-username)
              (submit-button "Email me a password reset link"))]))

(defn forgot-password [db {:keys [email-or-username]}]
  (when-let [user (find-user-by-user-or-email db email-or-username)]
    (let [reset-code (db/set-password-reset-code! db email-or-username)
          base-url (:base-url config)
          reset-password-url (str base-url "/password-resets/" reset-code)]
      (email/send-email (user :email)
        "Password reset for Clojars"
        (->> ["Hello,"
              "We received a request from someone, hopefully you, to reset the password of your clojars user."
              "To contine with the reset password process, click on the following link:"
              reset-password-url]
             (interpose "\n\n")
             (apply str)))))
  (html-doc nil "Forgot password?"
    [:h1 "Forgot password?"]
    [:p "If your account was found, you should get an email with a link to reset your password soon."]))

(defn edit-password-form [db reset-code & [errors]]
  (if-let [user (db/find-user-by-password-reset-code db reset-code)]
    (html-doc nil "Reset your password"
      [:div.small-section
       [:h1 "Reset your password"]
       (error-list errors)
       (form-to [:post (str "/password-resets/" reset-code)]
                (label :email-or-username "Your email")
                (text-field {:value (:email user)
                             :disabled "disabled"}
                            :ignored-email)
                (label :password "New password")
                (password-field {:placeholder "keep it secret, keep it safe"
                                 :required true}
                                :password)
                (label :confirm "Confirm new password")
                (password-field {:placeholder "confirm your password"
                                 :required true}
                                :confirm)
                (submit-button "Update my password"))])
    (html-doc nil "Reset your password"
      [:h1 "Reset your password"]
      [:p "The reset code was not found. Please ask for a new code in the " [:a {:href "/forgot-password"} "forgot password"] " page"])))

(defn update-password-validations [db confirm]
  [[:reset-code #(or (blank? %) (db/find-user-by-password-reset-code db %)) "The reset code does not exist or it has expired."]
   [:reset-code pred/present? "Reset code can't be blank."]
   [:password #(= % confirm) "Password and confirm password must match"]])

(defn edit-password [db reset-code {:keys [password confirm]}]
  (if-let [errors (apply validate {:password password
                                   :reset-code reset-code}
                         (update-password-validations db confirm))]
    (edit-password-form db reset-code (apply concat (vals errors)))
    (do
      (db/update-user-password db reset-code password)
      (assoc (redirect "/login")
             :flash "Your password was updated."))))
