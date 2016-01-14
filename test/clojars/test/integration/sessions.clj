(ns clojars.test.integration.sessions
  (:require [clojars.db :as db]
            [clojars.test.integration.steps :refer :all]
            [clojars.db :as db]
            [clojars.web :as web]
            [clojars.test.test-helper :as help]
            [clojure.test :refer :all]
            [kerodon
             [core :refer :all]
             [test :refer :all]]
            [clojure.java.jdbc :as jdbc]
            [net.cgrand.enlive-html :as enlive]))

(use-fixtures :each
  help/default-fixture
  help/with-clean-database)

(deftest user-cant-login-with-bad-user-pass-combo
  (-> (session (help/app))
      (login-as "fixture@example.org" "password")
      (follow-redirect)
      (has (status? 200))
      (within [:div :p.error]
              (has (text? "Incorrect username and/or password.")))))

(deftest user-can-login-and-logout
  (let [app (help/app)]
    (-> (session app)
        (register-as "fixture" "fixture@example.org" "password"))
    (doseq [login ["fixture"]]
      (-> (session app)
          (login-as login "password")
          (follow-redirect)
          (has (status? 200))
          (within [:.light-article :> :h1]
                  (has (text? "Dashboard (fixture)")))
          (follow "logout")
          (follow-redirect)
          (has (status? 200))
          (within [:nav [:li enlive/first-child] :a]
                  (has (text? "login")))))))

(deftest user-with-password-wipe-gets-message
  (let [app (help/app)]
    (-> (session app)
        (register-as "fixture" "fixture@example.org" "password"))
    (jdbc/db-do-commands help/*db*
                         "update users set password='' where user = 'fixture'")
    (-> (session app)
        (login-as "fixture" "password")
        (follow-redirect)
        (has (status? 200))
        (within [:div :p.error]
                (has (text? "Incorrect username and/or password."))))))
