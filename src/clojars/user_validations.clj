(ns clojars.user-validations
  (:require
   [clojars.db :as db]
   [clojars.hcaptcha :as hcaptcha]
   [clojars.hibp :as hibp]
   [clojars.log :as log]
   [clojars.models.permission :as permission-m]
   [clojars.models.user :as user-m]
   [clojure.string :as str]
   [valip.core :as valip]
   [valip.predicates :as pred]))

(def pwned-password-message
  (str "This password has appeared in a data breach and can't be used. "
       "Please choose a different password."))

(defn password-validations
  [hibp confirm]
  [[:password (pred/min-length 12) "Password must be 12 characters or longer"]
   [:password #(= % confirm) "Password and confirm password must match"]
   [:password (pred/max-length 256) "Password must be 256 or fewer characters"]
   [:password #(not (hibp/pwned? hibp %)) pwned-password-message]])

(defn user-validations
  ([db]
   (user-validations db nil))
  ([db existing-username]
   (let [existing-email-fn (if existing-username
                             #(let [existing-user (user-m/find-user-by-user-or-email db %)]
                                (or (nil? existing-user)
                                    (= existing-username (:user existing-user))))
                             #(nil? (user-m/find-user-by-user-or-email db %)))]
     [[:email pred/present? "Email can't be blank"]
      [:email pred/email-address? "Email is not valid"]
      [:email (pred/max-length 256) "Email must be 256 or fewer characters"]
      [:email existing-email-fn "A user already exists with this email"]
      [:username (pred/matches #"[a-z0-9_-]+")
       (str "Username must consist only of lowercase "
            "letters, numbers, hyphens and underscores.")]
      [:username pred/present? "Username can't be blank"]])))

(defn- captcha-validations
  [hcaptcha]
  [[:captcha (partial hcaptcha/valid-response? hcaptcha) "Captcha response is invalid."]])

(defn new-user-validations
  [db hcaptcha hibp confirm]
  (concat [[:password pred/present? "Password can't be blank"]
           [:username #(not (or (db/reserved-names %)
                                (user-m/find-user db %)
                                (seq (permission-m/group-activenames db %))))
            "Username is already taken"]]
          (user-validations db)
          (password-validations hibp confirm)
          (captcha-validations hcaptcha)))

(defn reset-password-validations
  [db hibp confirm]
  (concat
   [[:reset-code pred/present? "Reset code can't be blank."]
    [:reset-code #(or (str/blank? %) (user-m/find-user-by-password-reset-code db %)) "The reset code does not exist or it has expired."]]
   (password-validations hibp confirm)))

(def no-password-message
  "Your account doesn't have a password. Set a password via forgot password first.")

(defn current-password-validations
  [db username]
  (let [user (user-m/find-user db username)]
    (if (user-m/has-password? user)
      [[:current-password pred/present? "Current password can't be blank"]
       [:current-password #(user-m/password-matches? user %) "Current password is incorrect"]]
      [[:current-password (constantly false) no-password-message]])))

(defn- wrap-exceptions
  "Wraps validation predicates in a try/catch to reject invalid UTF8 strings."
  [[field pred message]]
  [field
   #(try
      (pred %)
      (catch Exception e
        (throw (ex-info "validation error"
                        {::validation-error? true
                         ::field field
                         ::pred pred
                         ::input %}
                        e))))
   message])

(defn validate
  [data validations]
  (try
    (apply valip/validate data (map wrap-exceptions validations))
    (catch Exception e
      (let [{::keys [field input pred validation-error?]} (ex-data e)]
        (if validation-error?
          (do
            (log/warn {:tag :validation-failed
                       :field field
                       :input input
                       :pred pred
                       :exception e})
            {field ["Invalid input"]})
          (throw e))))))
