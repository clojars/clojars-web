(ns clojars.test.integration.sessions
  (:require [clojure.test :refer :all]
            [kerodon.core :refer :all]
            [kerodon.test :refer :all]
            [clojars.test.integration.steps :refer :all]
            [clojars.web :as web]
            [clojars.test.test-helper :as help]
            [net.cgrand.enlive-html :as enlive]
            [net.cgrand.xml :as x]))

(help/use-fixtures)

(deftest user-cant-login-with-bad-user-pass-combo
  (-> (session web/clojars-app)
      (login-as "fixture@example.org" "password")
      (has (status? 200))
      (within [:article :div.error]
              (has (text? "Incorrect username or password.")))))

(deftest user-can-login-and-logout
  (-> (session web/clojars-app)
      (register-as "fixture" "fixture@example.org" "password" ""))
  (doseq [login ["fixture@example.org" "fixture"]]
    (-> (session web/clojars-app)
        (login-as login "password")
        (follow-redirect)
        (has (status? 200))
        (within [:article :h1]
                (has (text? "Dashboard (fixture)")))
        (follow "logout")
        (follow-redirect)
        (has (status? 200))
        (within [:nav [:li enlive/first-child] :a]
                (has (text? "login"))))))