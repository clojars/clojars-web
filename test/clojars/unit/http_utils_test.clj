(ns clojars.unit.http-utils-test
  (:require
   [clojars.http-utils :as http-utils]
   [clojure.test :refer [deftest is use-fixtures]]))

(def ^:private session-store-var #'http-utils/session-store-atom)

(use-fixtures :each
              (fn [f]
                (http-utils/clear-sessions!)
                (f)))

(deftest clear-sessions-for-user-removes-sessions-for-that-username
  (reset! @session-store-var
          {"k1" {:timestamp 1 :value {:cemerick.friend/identity {:username "alice"}}}
           "k2" {:timestamp 1 :value {:cemerick.friend/identity {:username "bob"}}}
           "k3" {:timestamp 1 :value {}}})
  (http-utils/clear-sessions-for-user! "alice")
  (is (= #{"k2" "k3"} (set (keys (deref @session-store-var))))))

(deftest clear-sessions-for-user-keeps-session-when-asked
  (reset! @session-store-var
          {"keep-me" {:timestamp 1 :value {:cemerick.friend/identity {:username "alice"}}}
           "drop-me" {:timestamp 1 :value {:cemerick.friend/identity {:username "alice"}}}})
  (http-utils/clear-sessions-for-user! "alice" :keep-session-key "keep-me")
  (is (= #{"keep-me"} (set (keys (deref @session-store-var))))))

(deftest clear-sessions-for-user-matches-string-identity
  (reset! @session-store-var
          {"reg" {:timestamp 1 :value {:cemerick.friend/identity "alice"}}})
  (http-utils/clear-sessions-for-user! "alice")
  (is (empty? (deref @session-store-var))))

(deftest clear-sessions-for-user-matches-friend-nested-identity
  (reset! @session-store-var
          {"sess" {:timestamp 1
                   :value {:cemerick.friend/identity
                           {:authentications {"bob" {:username "bob" :identity "bob"}}
                            :current "bob"}}}})
  (http-utils/clear-sessions-for-user! "bob")
  (is (empty? (deref @session-store-var))))
