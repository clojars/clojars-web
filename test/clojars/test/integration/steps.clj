(ns clojars.test.integration.steps
  (:require [cemerick.pomegranate.aether :as aether]
            [clojars.config :as config]
            [clojars.db :as db]
            [clojars.maven :as maven]
            [clojure.java.io :as io]
            [clojure.test :as test]
            [kerodon.core :refer :all]
            [clojars.config :refer [config]])
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

(defn inject-artifacts-into-repo! [user jar pom]
  (let [pom-file (io/resource pom)
        jarmap (maven/pom-to-map pom-file)]
    (db/add-jar user jarmap)
    (aether/deploy :coordinates [(keyword (:group jarmap)
                                          (:name jarmap))
                                 (:version jarmap)]
                   :jar-file (io/resource jar)
                   :pom-file pom-file
                   :repository {"local" (file-repo (:repo config))})))
