(ns clojars.test.integration.scp
 (:require [clojure.test :refer :all]
            [kerodon.core :refer :all]
            [kerodon.test :refer :all]
            [clojars.test.integration.steps :refer :all]
            [clojars.web :as web]
            [clojars.test.test-helper :as help]
            [net.cgrand.enlive-html :as enlive]
            [clojure.java.io :as io]
            [clojars.config :as config]
            [cemerick.pomegranate.aether :as aether]))

(use-fixtures :each help/default-fixture help/index-fixture)

(deftest user-can-register-and-scp
  (-> (session web/clojars-app)
      (register-as "dantheman" "test@example.org" "password" valid-ssh-key))
  (is (= (str "Welcome to Clojars, dantheman!\n\nDeploying fake/test 0.0.1\n\n"
              "Success! Your jars are now available from http://clojars.org/\n")
         (scp valid-ssh-key "test.jar" "test-0.0.1/test.pom")))
  (-> (session web/clojars-app)
      (visit "/groups/fake")
      (has (status? 200))
      (within [:body
               [:ul enlive/last-of-type]
               [:li enlive/only-child]
               :a]
              (has (text? "dantheman"))))
  (is (= 6
         (count
          (.list (io/file (:repo config/config) "fake" "test" "0.0.1")))))
  (help/delete-file-recursively help/local-repo)
  (is (= {'[fake/test "0.0.1"] nil}
         (aether/resolve-dependencies
          :coordinates '[[fake/test "0.0.1"]]
          :repositories {"local" (-> (:repo config/config)
                                     io/file
                                     .toURI
                                     .toString)}
          :local-repo help/local-repo))))

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
    (is (thrown? Exception (scp valid-ssh-key "test.jar" "test-0.0.1/test.pom")))
    (is (re-find #"Success! Your jars are now available"
                 (scp new-ssh-key "test.jar" "test-0.0.1/test.pom")))))

(deftest user-cannot-redeploy
  (-> (session web/clojars-app)
      (register-as "dantheman" "test@example.org" "password" valid-ssh-key))
  (is (re-find #"Success! Your jars are now available"
               (scp valid-ssh-key "test.jar" "test-0.0.1/test.pom")))
  (is (re-find #"Error: Redeploying .* is not allowed"
               (scp valid-ssh-key "test.jar" "test-0.0.1/test.pom"))))

(deftest user-can-redeploy-snapshots
  (-> (session web/clojars-app)
      (register-as "dantheman" "test@example.org" "password" valid-ssh-key))
  (is (re-find #"Success! Your jars are now available"
               (scp valid-ssh-key "test.jar" "test-0.0.3-SNAPSHOT/test.pom")))
  (is (re-find #"Success! Your jars are now available"
               (scp valid-ssh-key "test.jar" "test-0.0.3-SNAPSHOT/test.pom"))))

(deftest user-can-remove-key-and-scp-fails
  (-> (session web/clojars-app)
      (register-as "dantheman" "test@example.org" "password" valid-ssh-key)
      (follow-redirect)
      (follow "profile")
      (fill-in "Password:" "password")
      (fill-in "Confirm password:" "password")
      (fill-in "SSH public key:" "")
      (press "Update"))

  (is (thrown? Exception (scp valid-ssh-key "test.jar" "test-0.0.1/test.pom"))))

(deftest user-can-use-multiple-ssh-keys
  (let [valid-ssh-keys (str valid-ssh-key "0\n" valid-ssh-key "1")]
   (-> (session web/clojars-app)
       (register-as "dantheman" "test@example.org" "password" valid-ssh-keys)))
  (let [new-ssh-keys (str valid-ssh-key "3\n   \n" valid-ssh-key "4")]
    (-> (session web/clojars-app)
        (login-as "dantheman" "password")
        (follow-redirect)
        (follow "profile")
        (fill-in "Password:" "password")
        (fill-in "Confirm password:" "password")
        (fill-in "SSH public key:" new-ssh-keys)
        (press "Update"))
    (is (thrown? Exception (scp (str valid-ssh-key "1") "test.jar" "test-0.0.1/test.pom")))
    (is (= "Welcome to Clojars, dantheman!\n\nDeploying fake/test 0.0.1\n\nSuccess! Your jars are now available from http://clojars.org/\n"
           (scp (str valid-ssh-key "3") "test.jar" "test-0.0.1/test.pom")))
    (is (= "Welcome to Clojars, dantheman!\n\nDeploying fake/test 0.0.2\n\nSuccess! Your jars are now available from http://clojars.org/\n"
           (scp (str valid-ssh-key "4") "test.jar" "test-0.0.2/test.pom")))))

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
    (is (= "Welcome to Clojars, fixture!\n\nDeploying fake/test 0.0.2\nError: You don't have access to the fake group.\n"
           (scp ssh-key "test.jar" "test-0.0.2/test.pom")))))
