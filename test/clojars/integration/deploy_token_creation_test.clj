(ns clojars.integration.deploy-token-creation-test
  (:require
   [clojars.db :as db]
   [clojars.email :as email]
   [clojars.integration.steps :refer [create-deploy-token register-as]]
   ;; for defmethod side effects when handler runs
   [clojars.notifications.token]
   [clojars.test-helper :as help]
   [clojure.test :refer [deftest is use-fixtures]]
   [kerodon.core :refer [session visit within]]
   [kerodon.test :refer [has status? text?]]))

(use-fixtures :each
  help/default-fixture
  help/with-clean-database
  help/run-test-app)

(deftest creating-deploy-token-sends-email-to-user
  (db/add-user help/*db* "fixture@example.org" "fixture" "password1234")
  (create-deploy-token (session (help/app)) "fixture" "password1234" "my-laptop")
  (is (true? (email/wait-for-mock-emails)))
  (let [[to subject body] (first @email/mock-emails)]
    (is (= "fixture@example.org" to))
    (is (= "A deploy token was created on your Clojars account" subject))
    (is (re-find #"Hello," body))
    (is (re-find #"my-laptop" body))
    (is (re-find #"Scope: \* \(any group/artifact you have access to\)" body))
    (is (re-find #"Single use: no" body))
    (is (re-find #"Expires: never" body))
    (is (re-find #"https://clojars.org/tokens" body))
    (is (not (re-find #"CLOJARS_" body)))))

(defn- create-token
  [session params]
  (let [session (visit session "/tokens")
        [_ anti-forgery-token] (re-find #"name=\"__anti-forgery-token\".*?value=\"([^\"]+)\""
                                        (get-in session [:response :body]))]
    (visit session "/tokens"
           :request-method :post
           :params (assoc params :__anti-forgery-token anti-forgery-token))))

(deftest creating-deploy-token-with-invalid-params-is-rejected
  (let [session (-> (session (help/app))
                    (register-as "dantheman" "test@example.org" "password1234"))]
    (-> (create-token session {:name "a token"
                               :scope ""
                               :expires_in 999})
        (has (status? 200))
        (within [:div.error :ul :li]
          (has (text? "expires_in should be an empty string or should be either 1, 24, 168, 720 or 2160"))))

    (-> (create-token session {:name "a token"
                               :scope "arg"
                               :expires_in 1})
        (has (status? 200))
        (within [:div.error :ul :li]
          (has (text? "scope should be an empty string or should be of the form 'group/*' or 'group/project'"))))

    (-> (create-token session {:name "a token"
                               :scope ""
                               :expires_in ""
                               :single_use "nope"})
        (has (status? 200))
        (within [:div.error :ul :li]
          (has (text? "single_use should be a boolean"))))))

(deftest creating-deploy-token-with-invalid-scope-is-rejected
  (let [session (-> (session (help/app))
                    (register-as "dantheman" "test@example.org" "password1234"))]
    (-> (create-token session {:name "a token"
                               :scope "org.clojars.otheruser/*"
                               :expires_in ""})
        (has (status? 200))
        (within [:div.error :ul :li]
          (has (text? "scope should be for a group & project you have access to"))))))
