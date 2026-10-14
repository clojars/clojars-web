(ns clojars.unit.models.deploy-token-test
  (:require
   [buddy.core.codecs :as buddy.codecs]
   [buddy.core.hash :as buddy.hash]
   [clojars.db :as db]
   [clojars.models.deploy-token :as deploy-token-m]
   [clojars.models.user :as user-m]
   [clojars.test-helper :as help]
   [clojure.test :refer [deftest is use-fixtures]]
   [matcher-combinators.test]))

(use-fixtures :each
  help/with-clean-database)

(deftest deploy-token-creation-and-lookup
  (let [username "test-user"
        groupname "a-group"
        jarname "a-jar"
        tokenname "test-token"
        _ (user-m/add-user help/*db* "email@example.com" username "a-password")
        {:keys [token]} (deploy-token-m/add-deploy-token help/*db*
                                                         username tokenname groupname jarname false nil)

        {:keys [expires_at group_name jar_name single_use token_hash]}
        (deploy-token-m/find-token-by-value help/*db* token)]
    (is (nil? expires_at))
    (is (= groupname group_name))
    (is (= jarname jar_name))
    (is (= "no" single_use))
    (is (= (-> token buddy.hash/sha256 buddy.codecs/bytes->hex)
           token_hash))

    (let [expires-at-time (db/get-time)
          {:keys [token]} (deploy-token-m/add-deploy-token
                           help/*db*
                           username tokenname groupname jarname true expires-at-time)
          {:keys [expires_at single_use]} (deploy-token-m/find-token-by-value help/*db* token)]
      (is (= expires-at-time expires_at))
      (is (= "yes" single_use)))))
