(ns clojars.test.integration.steps
  (:require [cemerick.pomegranate.aether :as aether]
            [clj-time.core :as time]
            [clojars
             [config :refer [config]]
             [db :as db]
             [maven :as maven]]
            [clojars.test.test-helper :as help]
            [clojure.java.io :as io]
            [kerodon.core :refer :all])
  (:import java.io.File))

(defn login-as [state user password]
  (-> state
      (visit "/")
      (follow "login")
      (fill-in "Username" user)
      (fill-in "Password" password)
      (press "Login")))

(defn register-as
  ([state user email password]
     (register-as state user email password password))
  ([state user email password confirm]
     (-> state
         (visit "/")
         (follow "register")
         (fill-in "Email" email)
         (fill-in "Username" user)
         (fill-in "Password" password)
         (fill-in "Confirm password" confirm)
         (press "Register"))))

(defn file-repo [path]
  (str (.toURI (File. path))))

(defn inject-artifacts-into-repo! [db user jar pom]
  (let [pom-file (io/resource pom)
        jarmap (maven/pom-to-map pom-file)]
    (when-not (some #{user} (db/group-membernames db (:group jarmap)))
      (db/add-member db (:group jarmap) user "clojars-test"))
    (db/add-jar db user jarmap (time/now))
    (aether/deploy :coordinates [(keyword (:group jarmap)
                                          (:name jarmap))
                                 (:version jarmap)]
                   :jar-file (io/resource jar)
                   :pom-file pom-file
                   :local-repo help/local-repo
                   :repository {"local" (file-repo (:repo config))})))
