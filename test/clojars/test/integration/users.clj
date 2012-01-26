(ns clojars.test.integration.users
  (:use clojure.test
        kerodon.core
        clojars.test.integration.steps)
  (:require [clojars.web :as web]
            [clojars.test.test-helper :as help]
            [net.cgrand.enlive-html :as enlive]))

(help/use-fixtures)

(deftest user-can-register
  (-> (init :ring-app web/clojars-app)
      (register-as "dantheman" "test@example.org" "password" "")
      (follow-redirect)
      ;;TODO: should the user already be signed in?
      (validate (status (is= 200))
                ;; (html #(is (= ["Dashboard (dantheman)"]
                ;;               (map enlive/text
                ;;                    (enlive/select % [:article :h1])))))
                )))

(deftest bad-registration-info-should-show-error
  (-> (init :ring-app web/clojars-app)
      (register-as "fixture" "fixture@example.org" "password" ""))
  (-> (init :ring-app web/clojars-app)
      (request :get "/")
      (follow "register")
      (validate (status (is= 200))
                (html #(is (= [["Register | Clojars"]]
                              (map :content (enlive/select % [:title]))))))
      (fill-in "Email:" "test@example.org")
      (fill-in "Username:" "dantheman")
      (press "Register")
      (validate (status (is= 200))
                (html #(is (= ["Password can't be blank"]
                              (map enlive/text (enlive/select % [:article :div.error :ul :li]))))))
      (fill-in "Password:" "password")
      (press "Register")
      (validate (status (is= 200))
                (html #(is (= ["Password and confirm password must match"]
                              (map enlive/text (enlive/select % [:article :div.error :ul :li]))))))
      (fill-in "Email:" "")
      (fill-in "Password:" "password")
      (fill-in "Confirm password:" "password")
      (press "Register")
      (validate (status (is= 200))
                (html #(is (= ["Email can't be blank"]
                              (map enlive/text (enlive/select % [:article :div.error :ul :li]))))))
      (fill-in "Email:" "test@example.org")
      (fill-in "Username:" "")
      (fill-in "Password:" "password")
      (fill-in "Confirm password:" "password")
      (press "Register")
      (validate (status (is= 200))
                (html #(is (= ["Username must consist only of lowercase letters, numbers, hyphens and underscores."
                               "Username can't be blank"]
                              (map enlive/text (enlive/select % [:article :div.error :ul :li]))))))
      (fill-in "Username:" "<script>")
      (fill-in "Password:" "password")
      (fill-in "Confirm password:" "password")
      (press "Register")
      (validate (status (is= 200))
                (html #(is (= ["Username must consist only of lowercase letters, numbers, hyphens and underscores."]
                              (map enlive/text (enlive/select % [:article :div.error :ul :li]))))))
      (fill-in "Username:" "fixture")
      (fill-in "Password:" "password")
      (fill-in "Confirm password:" "password")
      (press "Register")
      (validate (status (is= 200))
                (html #(is (= ["Username is already taken"]
                              (map enlive/text (enlive/select % [:article :div.error :ul :li]))))))
      (fill-in "Username:" "dantheman")
      (fill-in "Password:" "password")
      (fill-in "Confirm password:" "password")
      (fill-in "SSH public key:" "asdf")
      (press "Register")
      (validate (status (is= 200))
                (html #(is (= ["Invalid SSH public key"]
                              (map enlive/text (enlive/select % [:article :div.error :ul :li]))))))))


(deftest user-can-update-info
  (-> (init :ring-app web/clojars-app)
      (register-as "fixture" "fixture@example.org" "password" ""))
  (-> (init :ring-app web/clojars-app)
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
      (validate
       (status (is= 200))
       (html #(is (= "login"
                     (first (map enlive/text
                                 (enlive/select % [:nav :a])))))))
      (login-as "fixture2@example.org" "password2")
      (follow-redirect)
      (validate (status (is= 200))
                (html #(is (= ["Dashboard (fixture)"]
                              (map enlive/text
                                   (enlive/select % [:article :h1]))))))))

(deftest bad-update-info-should-show-error
  (-> (init :ring-app web/clojars-app)
      (register-as "fixture" "fixture@example.org" "password" "")
      (login-as "fixture" "password")
      (follow-redirect)
      (follow "profile")
      (validate (status (is= 200))
                (html #(is (= [["Profile | Clojars"]]
                              (map :content (enlive/select % [:title]))))))
      (fill-in "Email:" "fixture2@example.org")
      (press "Update")
      (validate (status (is= 200))
                (html #(is (= ["Password can't be blank"]
                              (map enlive/text (enlive/select % [:article :div.error :ul :li]))))))
      (fill-in "Password:" "password")
      (press "Update")
      (validate (status (is= 200))
                (html #(is (= ["Password and confirm password must match"]
                              (map enlive/text (enlive/select % [:article :div.error :ul :li]))))))
      (fill-in "Email:" "")
      (fill-in "Password:" "password")
      (fill-in "Confirm password:" "password")
      (press "Update")
      (validate (status (is= 200))
                (html #(is (= ["Email can't be blank"]
                              (map enlive/text (enlive/select % [:article :div.error :ul :li]))))))
      (fill-in "Password:" "password")
      (fill-in "Confirm password:" "password")
      (fill-in "SSH public key:" "asdf")
      (press "Update")
      (validate (status (is= 200))
                (html #(is (= ["Invalid SSH public key"]
                              (map enlive/text (enlive/select % [:article :div.error :ul :li]))))))))

(deftest user-can-get-new-password
  (let [email (ref nil)]
    (binding [clojars.web.user/send-out (fn [x] (dosync (ref-set email x)))]
      (-> (init :ring-app web/clojars-app)
          (register-as "fixture" "fixture@example.org" "password" ""))
      (-> (init :ring-app web/clojars-app)
          (request :get "/")
          (follow "login")
          (follow "Forgot password?")
          (fill-in "Email or username:" "fixture")
          (press "Send new password")
          (validate (status (is= 200))
                    (html #(is (= ["If your account was found, you should get an email with a new password soon."]
                                  (map enlive/text
                                       (enlive/select % [:article :p])))))))
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
        (-> (init :ring-app web/clojars-app)
            (login-as "fixture@example.org" password)
            (follow-redirect)
            (validate (status (is= 200))
                      (html #(is (= ["Dashboard (fixture)"]
                                    (map enlive/text
                                         (enlive/select % [:article :h1])))))))))))

(deftest user-can-register-and-scp
  (-> (init :ring-app web/clojars-app)
      (register-as "dantheman" "test@example.org" "password" valid-ssh-key))
  (is (= "Welcome to Clojars, dantheman!\n\nDeploying fake/test 0.0.1\n\nSuccess! Your jars are now available from http://clojars.org/\n"
         (scp valid-ssh-key "test.jar" "test.pom")))
  (-> (init :ring-app web/clojars-app)
      (request :get "/groups/fake")
      (validate
       (html #(is (= "dantheman"
                     (last (map enlive/text
                                (enlive/select %
                                               [:ul :li :a])))))))))

(deftest user-can-update-and-scp
  (-> (init :ring-app web/clojars-app)
      (register-as "dantheman" "test@example.org" "password" valid-ssh-key))
  (let [new-ssh-key (str valid-ssh-key "0")]
    (-> (init :ring-app web/clojars-app)
        (login-as "dantheman" "password")
        (follow-redirect)
        (follow "profile")
        (fill-in "Password:" "password")
        (fill-in "Confirm password:" "password")
        (fill-in "SSH public key:" new-ssh-key)
        (press "Update"))
    (is (= "Welcome to Clojars, dantheman!\n\nDeploying fake/test 0.0.1\n\nSuccess! Your jars are now available from http://clojars.org/\n"
           (scp new-ssh-key "test.jar" "test.pom")))
    (is (thrown? Exception (scp valid-ssh-key "test.jar" "test.pom")))))

(deftest user-can-remove-key-and-scp-fails
  (-> (init :ring-app web/clojars-app)
      (register-as "dantheman" "test@example.org" "password" valid-ssh-key))
  (-> (init :ring-app web/clojars-app)
      (login-as "dantheman" "password")
      (follow-redirect)
      (follow "profile")
      (fill-in "Password:" "password")
      (fill-in "Confirm password:" "password")
      (fill-in "SSH public key:" "")
      (press "Update"))
  (is (thrown? Exception (scp valid-ssh-key "test.jar" "test.pom"))))

(deftest user-can-not-scp-to-group-they-are-not-a-member-of
  (-> (init :ring-app web/clojars-app)
      (register-as "dantheman" "test@example.org" "password" valid-ssh-key))
  (scp valid-ssh-key "test.jar" "test.pom")
  (let [ssh-key (str valid-ssh-key "1")]
    (-> (init :ring-app web/clojars-app)
        (register-as "fixture" "fixture@example.org" "password" ssh-key))
    (is (thrown-with-msg? Exception
          #"You don't have access to the fake group."
          (scp ssh-key "test.jar" "test.pom")))))

(deftest member-can-add-user-to-group
  (-> (init :ring-app web/clojars-app)
      (register-as "fixture" "fixture@example.org" "password" ""))
  (-> (init :ring-app web/clojars-app)
      (register-as "dantheman" "test@example.org" "password" "")
      (login-as "dantheman" "password")
      (request :get "/groups/org.clojars.dantheman")
      (fill-in "#user" "fixture")
      (press "add member")
      ;;(follow-redirect)
      (validate
       (html #(is (= "fixture"
                     (first (map enlive/text
                                (enlive/select %
                                               [:article :ul :li :a])))))))))

(deftest user-must-exist-to-be-added-to-group
  (-> (init :ring-app web/clojars-app)
      (register-as "dantheman" "test@example.org" "password" "")
      (login-as "dantheman" "password")
      (request :get "/groups/org.clojars.dantheman")
      (fill-in "#user" "fixture")
      (press "add member")
      (validate
       (html #(is (= "No such user: fixture"
                     (first (map enlive/text
                                 (enlive/select %
                                                [:div.error :ul :li])))))))))

;;TODO: (use pomegranate to)? verify scp'd file can be a dependency

(deftest users-can-be-viewed
  (-> (init :ring-app web/clojars-app)
      (register-as "dantheman" "test@example.org" "password" "")
      (request :get "/users/dantheman")
      (validate
       (html #(is (= "dantheman"
                     (last (map enlive/text
                                (enlive/select %
                                               [:h1])))))))))

(deftest jars-can-be-viewed
  (-> (init :ring-app web/clojars-app)
      (register-as "dantheman" "test@example.org" "password" valid-ssh-key))
  (scp valid-ssh-key "test.jar" "test.pom")
  (-> (init :ring-app web/clojars-app)
      (request :get "/fake/test")
      (validate
       (html #(is (= "fake/test"
                     (last (map enlive/text
                                (enlive/select %
                                               [:h1])))))))))

(deftest canonical-jars-can-be-viewed
  (-> (init :ring-app web/clojars-app)
      (register-as "dantheman" "test@example.org" "password" valid-ssh-key))
  (scp valid-ssh-key "fake.jar" "fake.pom")
  (-> (init :ring-app web/clojars-app)
      (request :get "/fake")
      (validate
       (html #(is (= "fake"
                     (last (map enlive/text
                                (enlive/select %
                                               [:h1])))))))))
