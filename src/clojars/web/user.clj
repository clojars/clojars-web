(ns clojars.web.user
  (:require
   [buddy.core.codecs.base64 :as base64]
   [cemerick.friend.credentials :as creds]
   [clojars.config :refer [config]]
   [clojars.db :as db :refer [find-group-verification
                              find-groupnames
                              find-user
                              find-user-by-user-or-email
                              group-activenames
                              jars-by-username
                              reserved-names
                              update-user
                              update-user-notifications]]
   [clojars.event :as event]
   [clojars.log :as log]
   [clojars.notifications.common :as notif-common]
   [clojars.web.common :refer [html-doc error-list form-table jar-link
                               flash group-link verified-group-badge-small]]
   [clojars.web.safe-hiccup :refer [form-to]]
   [clojure.string :as str :refer [blank?]]
   [hiccup.element :refer [unordered-list]]
   [hiccup.form :refer [label text-field
                        password-field
                        submit-button
                        email-field]]
   [one-time.qrgen :as qrgen]
   [ring.util.response :refer [redirect]]
   [valip.core :refer [validate]]
   [valip.predicates :as pred])
  (:import
   (java.io
    ByteArrayOutputStream)))

(set! *warn-on-reflection* true)

(defn register-form [{:keys [errors email username]} message]
  (html-doc "Register" {}
            [:div.small-section
             [:h1 "Register"]
             (flash message)
             (error-list errors)
             (form-to [:post "/register"]
                      (label :email "Email")
                      (email-field {:value email
                                    :required true
                                    :placeholder "bob@example.com"}
                                   :email)
                      (label :username "Username")
                      (text-field {:value username
                                   :required true
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
                      (submit-button "Register"))]))

;; Validations

(defn- password-validations [confirm]
  [[:password #(<= 8 (count %)) "Password must be 8 characters or longer"]
   [:password #(= % confirm) "Password and confirm password must match"]
   [:password #(> 256 (count %)) "Password must be 256 or fewer characters"]])

(def ^:private email-regex
  (re-pattern (str "(?i)[a-z0-9!#$%&'*+/=?^_`{|}~-]+"
                   "(?:\\.[a-z0-9!#$%&'*+/=?" "^_`{|}~-]+)*"
                   "@(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+"
                   "[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")))

(defn- validate-email
  [email]
  (and (string? email)
       (<= (.length ^String email) 254)
       (some? (re-matches email-regex email))))

(defn- user-validations
  ([db]
   (user-validations db nil))
  ([db existing-username]
   (let [existing-email-fn (if existing-username
                             #(let [existing-user (find-user-by-user-or-email db %)]
                                (or (nil? existing-user)
                                    (= existing-username (:user existing-user))))
                             #(nil? (find-user-by-user-or-email db %)))]
     [[:email pred/present? "Email can't be blank"]
      [:email validate-email "Email is not valid"]
      [:email existing-email-fn "A user already exists with this email"]
      [:username #(re-matches #"[a-z0-9_-]+" %)
       (str "Username must consist only of lowercase "
            "letters, numbers, hyphens and underscores.")]
      [:username pred/present? "Username can't be blank"]])))

(defn new-user-validations
  [db confirm]
  (concat [[:password pred/present? "Password can't be blank"]
           [:username #(not (or (reserved-names %)
                                (find-user db %)
                                (seq (group-activenames db %))))
            "Username is already taken"]]
          (user-validations db)
          (password-validations confirm)))

(defn reset-password-validations [db confirm]
  (concat
   [[:reset-code #(or (blank? %) (db/find-user-by-password-reset-code db %)) "The reset code does not exist or it has expired."]
    [:reset-code pred/present? "Reset code can't be blank."]]
   (password-validations confirm)))

(defn correct-password? [db username current-password]
  (let [user (find-user db username)]
    (when (and user (not (blank? current-password)))
      (creds/bcrypt-verify current-password (:password user)))))

(defn current-password-validations [db username]
  [[:current-password pred/present? "Current password can't be blank"]
   [:current-password #(correct-password? db username %)
    "Current password is incorrect"]])

(defn profile-form
  [account user flash-msg & [errors]]
  (let [heading (format "Profile (%s)" account)]
    (html-doc
     heading
     {:account account}
     [:div.small-section
      (flash flash-msg)
      [:h1 heading]
      (error-list errors)
      [:p "Your Clojars profile is just your email address and your password. You can change them here if you like."]
      (form-to [:post "/profile"]
               (label :email "Email")
               [:input {:type :email :name :email :id
                        :email :value (user :email)}]
               (label :current-password "Current password")
               (password-field :current-password)
               (label :password "New password")
               (password-field :password)
               (label :confirm "Confirm new password")
               (password-field :confirm)
               (submit-button "Update"))])))

(defn normalize-email
  [email]
  (when email
    (str/lower-case (str/trim email))))

(defn update-profile
  [db event-emitter account {:keys [email current-password password confirm] :as params} details]
  (let [email (normalize-email email)]
    (log/with-context {:tag :update-profile
                       :username account}
      (if-let [errors (apply validate {:email email
                                       :current-password current-password
                                       :username account
                                       :password password}
                             (concat
                              (user-validations db account)
                              (current-password-validations db account)
                              (when-not (blank? password)
                                (password-validations confirm))))]
        (do
          (log/info {:status :failed
                     :reason :validation-failed})
          (profile-form account params nil (apply concat (vals errors))))
        (let [old-email (:email (find-user-by-user-or-email db account))
              email-changed? (not= old-email email)
              password-changed? (seq password)]
          (update-user db account email password)
          (log/info {:status :success})
          (when email-changed?
            (event/emit event-emitter :email-changed
                        (merge {:username account
                                :old-email old-email}
                               details)))
          (when password-changed?
            (event/emit event-emitter :password-changed
                        (merge {:username account}
                               details)))
          (assoc (redirect "/profile")
                 :flash "Profile updated."))))))

(defn notifications-form
  [account user flash-msg & [errors]]
  (let [heading (format "Notification Preferences (%s)" account)]
    (html-doc heading
              {:account account}
              [:div.small-section
               (flash flash-msg)
               [:h1 heading]
               (error-list errors)
               (form-table
                [:post "/notification-preferences"]
                [[[:label {:for :send-deploy-emails}
                   "Receive deploy notification emails?"]
                  [:input {:type :checkbox
                           :name :send-deploy-emails
                           :id   :send-deploy-emails
                           :value 1
                           :checked (:send_deploy_emails user)}]]]
                (list [:p "If checked, you will receive an email notifying you of every deploy in any group you are a member of."]
                      (submit-button "Update")))])))

(defn update-notifications [db account {:keys [send-deploy-emails] :as _params}]
  (log/with-context {:tag :update-notification-preferences
                     :username account}
    (update-user-notifications db account {:send_deploy_emails (boolean send-deploy-emails)})
    (log/info {:status :success})
    (assoc (redirect "/notification-preferences")
           :flash "Notification preferences updated.")))

(defn show-user [db account user]
  (html-doc (user :user) {:account account}
            [:div.light-article.row
             [:h1.col-xs-12
              (user :user)]
             [:div.col-xs-12.col-sm-6
              [:h2 "Projects"]
              (unordered-list (map jar-link (jars-by-username db (user :user))))]
             [:div.col-xs-12.col-sm-6
              [:h2 "Groups"]
              (unordered-list (map (fn [group-name]
                                     (let [verified-group? (find-group-verification db group-name)]
                                       [:div
                                        [:span (group-link group-name)]
                                        (when verified-group?
                                          verified-group-badge-small)]))
                                   (find-groupnames db (user :user))))]]))

(defn forgot-password-form []
  (html-doc "Forgot password or username?" {}
            [:div.small-section
             [:h1 "Forgot something?"]
             [:p "Don't worry, it happens to the best of us. Enter your email or username below, and we'll send you a password reset link along with your username."]
             (form-to [:post "/forgot-password"]
                      (label :email-or-username "Email or Username")
                      (text-field {:placeholder "bob"
                                   :required true}
                                  :email-or-username)
                      (submit-button "Email me a password reset link"))]))

(defn forgot-password [db mailer {:keys [email-or-username]} details]
  (log/with-context {:email-or-username email-or-username}
    (if-let [user (find-user-by-user-or-email db email-or-username)]
      (let [reset-code (db/set-password-reset-code! db (:user user))
            base-url (:base-url (config))
            reset-password-url (str base-url "/password-resets/" reset-code)]
        (log/info {:tag :password-reset-code-generated})
        (mailer (:email user)
                "Password reset for Clojars"
                (->> ["Hello,"
                      (format "We received a request from someone, hopefully you, to reset the password of the clojars user: %s." (:user user))
                      "To continue with the reset password process, click on the following link:"
                      reset-password-url
                      "This link is valid for 24 hours, after which you will need to generate a new one."
                      (notif-common/details-table details)
                      "If you didn't reset your password then you can ignore this email."]
                     (interpose "\n\n")
                     (apply str))))
      (log/info {:tag :password-reset-user-not-found})))
  (html-doc "Forgot password?" {}
            [:div.small-section [:h1 "Forgot password?"]
             [:p "If your account was found, you should get an email with a link to reset your password soon."]]))

(defn reset-password-form [db reset-code & [errors]]
  (if-let [user (db/find-user-by-password-reset-code db reset-code)]
    (html-doc "Reset your password" {:footer-links? false}
              [:div.small-section
               [:h1 "Reset your password"]
               (error-list errors)
               (form-to [:post (str "/password-resets/" reset-code)]
                        (label :username "Your username")
                        (text-field {:value (:user user)
                                     :disabled "disabled"}
                                    :ignored-username)
                        (label :email "Your email")
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
    (do
      (log/info {:tag :password-reset-bad-code
                 :reset-code reset-code})
      (html-doc "Reset your password" {}
                [:h1 "Reset your password"]
                [:p "The reset code was not found. Please ask for a new code in the " [:a {:href "/forgot-password"} "forgot password"] " page"]))))

(defn reset-password [db event-emitter reset-code {:keys [password confirm]} details]
  (log/with-context {:tag :reset-password
                     :reset-code reset-code}
    (if-let [errors (apply validate {:password password
                                     :reset-code reset-code}
                           (reset-password-validations db confirm))]
      (do
        (log/info {:status :failed
                   :reason :validation-failed})
        (reset-password-form db reset-code (apply concat (vals errors))))
      (let [{username :user} (db/find-user-by-password-reset-code db reset-code)]
        (db/reset-user-password db username reset-code password)
        (log/info {:status :success
                   :username username})
        (event/emit event-emitter :password-changed
                    (merge {:username username}
                           details))
        (assoc (redirect "/login")
               :flash "Your password was updated.")))))

;; ---- mfa ----

(defn recovery-code-message
  [recovery-code]
  (when recovery-code
    (list
     [:p "Two-factor authentication has been enabled. If you don't have access to your two-factor device, you can use the following code (along with your password) to log in. This code can only be used once, and using it will disable two-factor auth for your account. The recovery code will only be shown this one time, so be sure to copy it now and put it in a safe place:"]
     [:div.new-token
      [:pre recovery-code]])))

(defn mfa
  [account {:as _user :keys [otp_active]} flash-msg]
  (let [heading (format "Two-Factor Authentication (%s)" account)]
    (html-doc
     heading
     {:account account}
     [:div.small-section
      (flash flash-msg)
      [:h1 heading]
      [:div.help
       [:p
        "With two-factor authentication, you can set up a Time-based One Time Password (TOTP) device "
        "that will generate tokens you can use in addition to your password to log in."]]
      [:p "Two-factor authentication is currently "
       [:strong (if otp_active "enabled." "disabled.")]
       (format " To %s it, enter your password." (if otp_active "disable" "enable"))]
      (form-to [(if otp_active :delete :post) "/mfa"]
               (label :password "Password")
               (password-field {:required true}
                               :password)
               (submit-button (format "%s two-factor authentication"
                                      (if otp_active "Disable" "Enable"))))])))

(defn setup-mfa
  [account {:as _user :keys [email otp_secret_key]} flash-msg]
  (let [^ByteArrayOutputStream qrcode-stream (qrgen/totp-stream
                                              {:image-type :PNG
                                               :image-size 300
                                               :label "Clojars.org"
                                               :user email
                                               :secret otp_secret_key})
        qrcode-b64 (-> qrcode-stream
                       (.toByteArray)
                       (base64/encode)
                       (as-> ^bytes % (String. %)))
        heading (format "Two-Factor Authentication (%s)" account)]
    (html-doc
     heading
     {:account account}
     [:div.small-section
      (flash flash-msg)
      [:h1 heading]
      [:p "Scan this QR code with your chosen authenticator:"]
      [:img {:src (format "data:image/png;base64,%s" qrcode-b64)}]
      [:p "Can't scan the QR code? Enter your key manually into your two-factor device: "
       [:pre.mfa-key otp_secret_key]]
      [:p "Once you have your two-factor device configured, enter a code it generates below to complete the setup."
       [:strong "Two-factor auth will not be enabled on your account if you don't complete this step."]]
      (form-to [:put "/mfa"]
               (label :otp "Code")
               (text-field {:required true}
                           :otp)
               (submit-button "Confirm code"))])))
