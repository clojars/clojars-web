(ns clojars.user-validations
  (:require
   [cemerick.friend.credentials :as creds]
   [clojars.db :as db]
   [clojars.hcaptcha :as hcaptcha]
   [clojars.log :as log]
   [clojure.string :as str]
   [valip.core :as valip]
   [valip.predicates :as pred]))

(defn password-validations
  [confirm]
  [[:password (pred/min-length 8) "Password must be 8 characters or longer"]
   [:password #(= % confirm) "Password and confirm password must match"]
   [:password (pred/max-length 256) "Password must be 256 or fewer characters"]])

(defn user-validations
  ([db]
   (user-validations db nil))
  ([db existing-username]
   (let [existing-email-fn (if existing-username
                             #(let [existing-user (db/find-user-by-user-or-email db %)]
                                (or (nil? existing-user)
                                    (= existing-username (:user existing-user))))
                             #(nil? (db/find-user-by-user-or-email db %)))]
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
  [db hcaptcha confirm]
  (concat [[:password pred/present? "Password can't be blank"]
           [:username #(not (or (db/reserved-names %)
                                (db/find-user db %)
                                (seq (db/group-activenames db %))))
            "Username is already taken"]]
          (user-validations db)
          (password-validations confirm)
          (captcha-validations hcaptcha)))

(defn reset-password-validations
  [db confirm]
  (concat
   [[:reset-code pred/present? "Reset code can't be blank."]
    [:reset-code #(or (str/blank? %) (db/find-user-by-password-reset-code db %)) "The reset code does not exist or it has expired."]]
   (password-validations confirm)))

(defn- correct-password?
  [db username current-password]
  (let [user (db/find-user db username)]
    (when (and user (not (str/blank? current-password)))
      (creds/bcrypt-verify current-password (:password user)))))

(defn current-password-validations
  [db username]
  [[:current-password pred/present? "Current password can't be blank"]
   [:current-password #(correct-password? db username %) "Current password is incorrect"]])

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
