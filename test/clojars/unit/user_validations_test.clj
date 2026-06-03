(ns clojars.unit.user-validations-test
  (:require
   [clojars.db :as db]
   [clojars.hcaptcha :as hcaptcha]
   [clojars.hibp :as hibp]
   [clojars.remote-service :as remote-service]
   [clojars.test-helper :as help]
   [clojars.user-validations :as uv]
   [clojure.test :refer [is deftest use-fixtures]]
   [matcher-combinators.test]))

(use-fixtures :each
  help/with-clean-database)

(defn- mock-hibp [& pwned]
  (hibp/new-mock-hibp (atom (zipmap pwned (repeat 1)))))

(deftest test-password-validations
  (let [hibp (mock-hibp)]
    (is (nil? (uv/validate {:password "abcdefghijkl"} (uv/password-validations hibp "abcdefghijkl"))))
    (is (match?
         {:password ["Password must be 12 characters or longer"]}
         (uv/validate {:password "a"} (uv/password-validations hibp "a"))))
    (is (match?
         {:password ["Password must be 12 characters or longer"
                     "Password and confirm password must match"]}
         (uv/validate {:password "a"} (uv/password-validations hibp "b"))))
    (let [long-password (apply str (repeat 257 "a"))]
      (is (match?
           {:password ["Password must be 256 or fewer characters"]}
           (uv/validate {:password long-password} (uv/password-validations hibp long-password)))))))

(deftest test-pwned-password-rejected
  (let [hibp (mock-hibp "leaked-password-123")]
    (is (match?
         {:password [#"known data breach"]}
         (uv/validate {:password "leaked-password-123"}
                      (uv/password-validations hibp "leaked-password-123"))))))

(deftest test-user-validations
  (is (nil? (uv/validate {:email "foo@example.com"
                          :username "auser"}
                         (uv/user-validations help/*db*))))
  (db/add-user help/*db* "foo@example.com" "auser" "password1234a")
  (is (match?
       {:email ["A user already exists with this email"]}
       (uv/validate {:email "foo@example.com"
                     :username "auser"}
                    (uv/user-validations help/*db*))))
  (is (nil?
       (uv/validate {:email "foo@example.com"
                     :username "auser"}
                    (uv/user-validations help/*db* "auser"))))
  (is (match?
       {:email ["Email can't be blank"
                "Email is not valid"]
        :username ["Username must consist only of lowercase letters, numbers, hyphens and underscores."
                   "Username can't be blank"]}
       (uv/validate {:email ""
                     :username ""}
                    (uv/user-validations help/*db*))))
  (is (match?
       {:email ["Email is not valid"]
        :username ["Username must consist only of lowercase letters, numbers, hyphens and underscores."]}
       (uv/validate {:email "foo@@"
                     :username "a User"}
                    (uv/user-validations help/*db*))))
  (is (match?
       {:email ["Email must be 256 or fewer characters"]}
       (uv/validate {:email (format "foo@%s.com" (apply str (repeat 254 "a")))
                     :username "auser2"}
                    (uv/user-validations help/*db*))))
  ;; Tests that we protect against fuzzing on the registration form
  (is (match?
       {:email ["Invalid input"]}
       ;; This is valid UT, but postgres does not allow null bytes in strings,
       ;; so will throw
       (uv/validate {:email (String. (byte-array [0x00]))
                     :username "auser2"}
                    (uv/user-validations help/*db*)))))

(deftest test-new-user-validations
  (remote-service/with-mocking
    (let [hcaptcha-service (hcaptcha/new-mock-hcaptcha)
          hibp (mock-hibp)]
      (remote-service/set-responder '-validate-hcaptcha-response
                                    (fn [request-info]
                                      {:success (= "valid" (get-in request-info [:form-params :response]))}))
      (is (nil?
           (uv/validate {:email "foo@example.com"
                         :password "password1234a"
                         :username "auser"
                         :captcha "valid"}
                        (uv/new-user-validations help/*db* hcaptcha-service hibp "password1234a"))))
      (is (match?
           {:captcha ["Captcha response is invalid."]}
           (uv/validate {:email "foo@example.com"
                         :password "password1234a"
                         :username "auser"
                         :captcha "invalid"}
                        (uv/new-user-validations help/*db* hcaptcha-service hibp "password1234a"))))
      (is (match?
           {:password ["Password can't be blank"
                       "Password must be 12 characters or longer"
                       "Password and confirm password must match"]}
           (uv/validate {:email "foo@example.com"
                         :password ""
                         :username "auser"
                         :captcha "valid"}
                        (uv/new-user-validations help/*db* hcaptcha-service hibp "password1234a"))))
      (db/add-user help/*db* "foo@example.com" "auser" "password1234a")
      (is (match?
           {:username ["Username is already taken"]
            :email ["A user already exists with this email"]}
           (uv/validate {:email "foo@example.com"
                         :password "password1234a"
                         :username "auser"
                         :captcha "valid"}
                        (uv/new-user-validations help/*db* hcaptcha-service hibp "password1234a"))))
      (is (match?
           {:username ["Username is already taken"]}
           (uv/validate {:email "foo2@example.com"
                         :password "password1234a"
                         :username "admin"
                         :captcha "valid"}
                        (uv/new-user-validations help/*db* hcaptcha-service hibp "password1234a"))))
      (db/add-group help/*db* "auser" "agroup")
      (is (match?
           {:username ["Username is already taken"]}
           (uv/validate {:email "foo2@example.com"
                         :password "password1234a"
                         :username "agroup"
                         :captcha "valid"}
                        (uv/new-user-validations help/*db* hcaptcha-service hibp "password1234a")))))))

(deftest test-reset-password-validations
  (db/add-user help/*db* "foo@example.com" "auser" "password1234a")
  (let [reset-code (db/set-password-reset-code! help/*db* "auser")
        hibp (mock-hibp)]
    (is (nil?
         (uv/validate {:reset-code reset-code
                       :password "password1234a"}
                      (uv/reset-password-validations help/*db* hibp "password1234a"))))
    (is (match?
         {:reset-code ["Reset code can't be blank."]}
         (uv/validate {:reset-code ""
                       :password "password1234a"}
                      (uv/reset-password-validations help/*db* hibp "password1234a"))))
    (is (match?
         {:reset-code ["The reset code does not exist or it has expired."]}
         (uv/validate {:reset-code "foo"
                       :password "password1234a"}
                      (uv/reset-password-validations help/*db* hibp "password1234a"))))))

(deftest current-password-validations
  (db/add-user help/*db* "foo@example.com" "auser" "password1234a")
  (is (nil?
       (uv/validate {:current-password "password1234a"}
                    (uv/current-password-validations help/*db* "auser"))))
  (is (match?
       {:current-password ["Current password can't be blank"
                           "Current password is incorrect"]}
       (uv/validate {:current-password ""}
                    (uv/current-password-validations help/*db* "auser"))))
  (is (match?
       {:current-password ["Current password is incorrect"]}
       (uv/validate {:current-password "wrong"}
                    (uv/current-password-validations help/*db* "auser")))))
