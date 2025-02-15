(ns clojars.user-validations
  (:require
   [cemerick.friend.credentials :as creds]
   [clojars.db :as db]
   [clojars.hcaptcha :as hcaptcha]
   [clojure.string :as str]
   [valip.core :as valip]
   [valip.predicates :as pred]))

(defn password-validations
  [confirm]
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
      [:email validate-email "Email is not valid"]
      [:email existing-email-fn "A user already exists with this email"]
      [:username #(re-matches #"[a-z0-9_-]+" %)
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

(defn validate
  [data validations]
  (apply valip/validate data validations))
