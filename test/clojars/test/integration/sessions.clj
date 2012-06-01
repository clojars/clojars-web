(ns clojars.test.integration.sessions
  (:require [clojure.test :refer :all]
            [kerodon.core :refer :all]
            [kerodon.test :refer :all]
            [clojars.test.integration.steps :refer :all]
            [clojars.db :as db]
            [clojars.web :as web]
            [clojars.test.test-helper :as help]
            [net.cgrand.enlive-html :as enlive]
            [korma.core :as korma]))

(help/use-fixtures)

(deftest user-cant-login-with-bad-user-pass-combo
  (-> (session web/clojars-app)
      (login-as "fixture@example.org" "password")
      (follow-redirect)
      (has (status? 200))
      (within [:article :div :p.error]
              (has (text? "Incorrect username and/or password.")))))

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

(deftest user-with-password-wipe-gets-message
  (-> (session web/clojars-app)
      (register-as "fixture" "fixture@example.org" "password" ""))
  (korma/update db/users
                (korma/set-fields {:password ""})
                (korma/where {:user "fixture"}))
  (-> (session web/clojars-app)
      (login-as "fixture" "password")
      (follow-redirect)
      (has (status? 200))
      (within [:article :div :p.error]
              (has (text? "Incorrect username and/or password.")))))