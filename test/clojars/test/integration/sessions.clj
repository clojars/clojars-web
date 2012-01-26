(ns clojars.test.integration.sessions
  (:use clojure.test
        kerodon.core
        clojars.test.integration.steps)
  (:require [clojars.web :as web]
            [clojars.test.test-helper :as help]
            [net.cgrand.enlive-html :as enlive]))

(help/use-fixtures)

(deftest user-cant-login-with-bad-user-pass-combo
  (-> (init :ring-app web/clojars-app)
      (login-as "fixture@example.org" "password")
      (validate (status (is= 200))
                (html #(is (= ["Incorrect username or password."]
                              (map enlive/text
                                   (enlive/select % [:article :div.error]))))))))

(deftest user-can-login-and-logout
  (-> (init :ring-app web/clojars-app)
      (register-as "fixture" "fixture@example.org" "password" ""))
  (doseq [login ["fixture@example.org" "fixture"]]
    (-> (init :ring-app web/clojars-app)
        (login-as login "password")
        (follow-redirect)
        (validate (status (is= 200))
                  (html #(is (= ["Dashboard (fixture)"]
                                (map enlive/text
                                     (enlive/select % [:article :h1]))))))
        (follow "logout")
        (follow-redirect)
        (validate
         (status (is= 200))
         (html #(is (= "login"
                       (first (map enlive/text
                                   (enlive/select % [:nav :a]))))))))))