(ns clojars.test.integration.users
  (:use clojure.test
        kerodon.core
        kerodon.test
        clojars.test.integration.steps)
  (:require [clojars.web :as web]
            [clojars.test.test-helper :as help]
            [net.cgrand.enlive-html :as enlive]
            [clojure.java.io :as io]
            [clojars.config :as config]))

(help/use-fixtures)

(deftest user-can-register
  (-> (session web/clojars-app)
      (register-as "dantheman" "test@example.org" "password" "")
      (follow-redirect)
      ;;TODO: should the user already be signed in?
      (has (status? 200))
      ;; (within [:article :h1]
      ;;         (has (text? "Dashboard (dantheman)")))
      ))

(deftest bad-registration-info-should-show-error
  (-> (session web/clojars-app)
      (register-as "fixture" "fixture@example.org" "password" ""))
  (-> (session web/clojars-app)
      (visit "/")
      (follow "register")
      (has (status? 200))
      (within [:title]
              (has (text? "Register | Clojars")))

      (fill-in "Email:" "test@example.org")
      (fill-in "Username:" "dantheman")
      (press "Register")
      (has (status? 200))
      (within [:article :div.error :ul :li]
              (has (text? "Password can't be blank")))

      (fill-in "Password:" "password")
      (press "Register")
      (has (status? 200))
      (within [:article :div.error :ul :li]
              (has (text? "Password and confirm password must match")))

      (fill-in "Email:" "")
      (fill-in "Password:" "password")
      (fill-in "Confirm password:" "password")
      (press "Register")
      (has (status? 200))
      (within [:article :div.error :ul :li]
              (has (text? "Email can't be blank")))

      (fill-in "Email:" "test@example.org")
      (fill-in "Username:" "")
      (fill-in "Password:" "password")
      (fill-in "Confirm password:" "password")
      (press "Register")
      (has (status? 200))
      (within [:article :div.error :ul :li]
              (has (text? "Username must consist only of lowercase letters, numbers, hyphens and underscores.Username can't be blank")))
      (fill-in "Username:" "<script>")
      (fill-in "Password:" "password")
      (fill-in "Confirm password:" "password")
      (press "Register")
      (has (status? 200))
      (within [:article :div.error :ul :li]
              (has (text? "Username must consist only of lowercase letters, numbers, hyphens and underscores.")))

      (fill-in "Username:" "fixture")
      (fill-in "Password:" "password")
      (fill-in "Confirm password:" "password")
      (press "Register")
      (has (status? 200))
      (within [:article :div.error :ul :li]
              (has (text? "Username is already taken")))

      (fill-in "Username:" "dantheman")
      (fill-in "Password:" "password")
      (fill-in "Confirm password:" "password")
      (fill-in "SSH public key:" "asdf")
      (press "Register")
      (has (status? 200))
      (within [:article :div.error :ul :li]
              (has (text? "Invalid SSH public key")))))

(deftest user-can-update-info
  (-> (session web/clojars-app)
      (register-as "fixture" "fixture@example.org" "password" ""))
  (-> (session web/clojars-app)
      (login-as "fixture" "password")
      (follow-redirect)
      (follow "profile")
      (fill-in "Email:" "fixture2@example.org")
      (fill-in "Password:" "password2")
      (fill-in "Confirm password:" "password2")
      (press "Update")
      (follow-redirect)
      (follow "logout")
      (follow-redirect)
      (has (status? 200))
      (within [:nav [:li enlive/first-child] :a]
              (has (text? "login")))
      (login-as "fixture2@example.org" "password2")
      (follow-redirect)
      (has (status? 200))
      (within [:article :h1]
              (has (text? "Dashboard (fixture)")))))

(deftest bad-update-info-should-show-error
  (-> (session web/clojars-app)
      (register-as "fixture" "fixture@example.org" "password" "")
      (login-as "fixture" "password")
      (follow-redirect)
      (follow "profile")
      (has (status? 200))
      (within [:title]
              (has (text? "Profile | Clojars")))

      (fill-in "Email:" "fixture2@example.org")
      (press "Update")
      (has (status? 200))
      (within [:article :div.error :ul :li]
              (has (text? "Password can't be blank")))

      (fill-in "Password:" "password")
      (press "Update")
      (has (status? 200))
      (within [:article :div.error :ul :li]
              (has (text? "Password and confirm password must match")))

      (fill-in "Email:" "")
      (fill-in "Password:" "password")
      (fill-in "Confirm password:" "password")
      (press "Update")
      (has (status? 200))
      (within [:article :div.error :ul :li]
              (has (text? "Email can't be blank")))

      (fill-in "Password:" "password")
      (fill-in "Confirm password:" "password")
      (fill-in "SSH public key:" "asdf")
      (press "Update")
      (has (status? 200))
      (within [:article :div.error :ul :li]
              (has (text? "Invalid SSH public key")))))

(deftest user-can-get-new-password
  (let [email (ref nil)]
    (binding [clojars.web.user/send-out (fn [x] (dosync (ref-set email x)))]
      (-> (session web/clojars-app)
          (register-as "fixture" "fixture@example.org" "password" ""))
      (-> (session web/clojars-app)
          (visit "/")
          (follow "login")
          (follow "Forgot password?")
          (fill-in "Email or username:" "fixture")
          (press "Send new password")
          (has (status? 200))
          (within [:article :p]
                  (has (text? "If your account was found, you should get an email with a new password soon."))))
      (is @email)
      (let [from (.getFromAddress @email)]
        (is (= (.getAddress from) "noreply@clojars.org"))
        (is (= (.getPersonal from) "Clojars")))
      (let [to (first (.getToAddresses @email))]
        (is (= (.getAddress to) "fixture@example.org")))
      (is (= (.getSubject @email)
             "Password reset for Clojars"))
      (.buildMimeMessage @email)
      (let [[msg password] (re-find
                            #"Hello,\n\nYour new password for Clojars is: ([^ ]+)\n\nKeep it safe this time."
                            (.getContent (.getMimeMessage @email)))]
        (is msg)
        (-> (session web/clojars-app)
            (login-as "fixture@example.org" password)
            (follow-redirect)
            (has (status? 200))
            (within [:article :h1]
                    (has (text? "Dashboard (fixture)"))))))))

(deftest user-can-register-and-scp
  (-> (session web/clojars-app)
      (register-as "dantheman" "test@example.org" "password" valid-ssh-key))

    (is (= "Welcome to Clojars, dantheman!\n\nDeploying fake/test 0.0.1\n\nSuccess! Your jars are now available from http://clojars.org/\n"
           (scp valid-ssh-key "test.jar" "test-0.0.1/test.pom")))
  (-> (session web/clojars-app)
      (visit "/groups/fake")
      (has (status? 200))
      (within [:article [:ul enlive/last-of-type] [:li enlive/last-child] :a]
              (has (text? "dantheman"))))
  ;;TODO: (use pomegranate to)? verify scp'd file can be a dependency
  ;;in the mean time here is a simple test to see something was added
  ;;to the repo
  (is (= 6
         (count
          (.list (io/file (:repo config/config) "fake" "test" "0.0.1"))))))

(deftest user-can-update-and-scp
  (-> (session web/clojars-app)
      (register-as "dantheman" "test@example.org" "password" valid-ssh-key))
  (let [new-ssh-key (str valid-ssh-key "0")]
    (-> (session web/clojars-app)
        (login-as "dantheman" "password")
        (follow-redirect)
        (follow "profile")
        (fill-in "Password:" "password")
        (fill-in "Confirm password:" "password")
        (fill-in "SSH public key:" new-ssh-key)
        (press "Update"))
    (is (= "Welcome to Clojars, dantheman!\n\nDeploying fake/test 0.0.1\n\nSuccess! Your jars are now available from http://clojars.org/\n"
           (scp new-ssh-key "test.jar" "test-0.0.1/test.pom")))
    (is (thrown? Exception (scp valid-ssh-key "test.jar" "test-0.0.1/test.pom")))))

(deftest user-can-remove-key-and-scp-fails
  (-> (session web/clojars-app)
      (register-as "dantheman" "test@example.org" "password" valid-ssh-key))
  (-> (session web/clojars-app)
      (login-as "dantheman" "password")
      (follow-redirect)
      (follow "profile")
      (fill-in "Password:" "password")
      (fill-in "Confirm password:" "password")
      (fill-in "SSH public key:" "")
      (press "Update"))
  (is (thrown? Exception (scp valid-ssh-key "test.jar" "test-0.0.1/test.pom"))))

(deftest scp-wants-filenames-in-specific-format
  (-> (session web/clojars-app)
      (register-as "dantheman" "test@example.org" "password" valid-ssh-key))
  (is (= "Welcome to Clojars, dantheman!\nError: You need to give me one of: [\"test-0.0.1.jar\" \"test.jar\"]\n"
         (scp valid-ssh-key "fake.jar" "test-0.0.1/test.pom"))))

(deftest user-can-not-scp-to-group-they-are-not-a-member-of
  (-> (session web/clojars-app)
      (register-as "dantheman" "test@example.org" "password" valid-ssh-key))
  (scp valid-ssh-key "test.jar" "test-0.0.1/test.pom")
  (let [ssh-key (str valid-ssh-key "1")]
    (-> (session web/clojars-app)
        (register-as "fixture" "fixture@example.org" "password" ssh-key))
    (is (= "Welcome to Clojars, fixture!\n\nDeploying fake/test 0.0.1\nError: You don't have access to the fake group.\n"
           (scp ssh-key "test.jar" "test-0.0.1/test.pom")))))


(deftest member-can-add-user-to-group
  (-> (session web/clojars-app)
      (register-as "fixture" "fixture@example.org" "password" ""))
  (-> (session web/clojars-app)
      (register-as "dantheman" "test@example.org" "password" "")
      (login-as "dantheman" "password")
      (visit "/groups/org.clojars.dantheman")
      (fill-in [:#user] "fixture")
      (press "add member")
      ;;(follow-redirect)
      (within [:article :ul [:li enlive/first-child] :a]
              (has (text? "fixture")))))

(deftest user-must-exist-to-be-added-to-group
  (-> (session web/clojars-app)
      (register-as "dantheman" "test@example.org" "password" "")
      (login-as "dantheman" "password")
      (visit "/groups/org.clojars.dantheman")
      (fill-in [:#user] "fixture")
      (press "add member")
      (within [:div.error :ul :li]
              (has (text? "No such user: fixture")))))

(deftest users-can-be-viewed
  (-> (session web/clojars-app)
      (register-as "dantheman" "test@example.org" "password" "")
      (visit "/users/dantheman")
      (within [:article :h1]
              (has (text? "dantheman")))))
