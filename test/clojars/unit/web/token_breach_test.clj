(ns clojars.unit.web.token-breach-test
  (:require
   [buddy.core.codecs.base64 :as base64]
   [buddy.core.dsa :as dsa]
   [buddy.core.keys :as keys]
   [cheshire.core :as json]
   [clj-http.client :as client]
   [clojars.db :as db]
   [clojars.email :as email]
   ;; for defmethods
   [clojars.notifications.token]
   [clojars.test-helper :as help]
   [clojars.util :refer [filter-some]]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring.mock.request :refer [body header request]]))

(use-fixtures :each
  help/default-fixture
  help/with-clean-database
  help/run-test-app)

(def privkey (keys/private-key (io/resource "ecdsa-key.pem")))
(def pubkey-str (slurp (io/resource "ecdsa-key-pub.pem")))
(def github-response
  {:public_keys [{:key_identifier "abcd"
                  :key pubkey-str
                  :is_current true}]})

(defn- build-breach-request
  [& token-values]
  (let [payload (mapv (fn [token-value]
                        {:token token-value
                         :type "whatever"
                         :url "https://github.com/foo/bar"})
                      token-values)
        payload-str (json/encode payload)
        sig (dsa/sign payload-str {:key privkey :alg :ecdsa+sha256})
        sig-b64 (String. (base64/encode sig))]
    (-> (request :post "/token-breach/github")
        (body payload-str)
        (header "Content-Type" "application/json")
        (header "GITHUB-PUBLIC-KEY-IDENTIFIER" "abcd")
        (header "GITHUB-PUBLIC-KEY-SIGNATURE" sig-b64))))

(defn- find-token [username token-name]
  (filter-some #(= token-name (:name %))
               (db/find-user-tokens-by-username help/*db* username)))

(deftest test-github-token-breach-request-with-invalid-identifier
  (with-redefs [client/get (constantly {:body github-response})]
    (let [app (help/app)
          request (-> (build-breach-request "whatever")
                      (header "GITHUB-PUBLIC-KEY-IDENTIFIER" "bad"))
          res (app request)]
      (is (= 422 (:status res))))))

(deftest test-github-token-breach-reporting-works
  (let [_user (db/add-user help/*db* "ham@biscuit.co" "ham" "biscuit")
        app (help/app)]
    (with-redefs [client/get (constantly {:body github-response})]
      (testing "when token is enabled"
        (let [token (db/add-deploy-token help/*db* "ham" "a token" nil nil false nil)
              token-str (:token token)
              res (app (build-breach-request token-str))
              db-token (find-token "ham" "a token")
              _ (is (true? (email/wait-for-mock-emails)))
              [to subject message] (first @email/mock-emails)]
          (is (= 200 (:status res)))
          (is (= [{:token_raw token-str
                   :token_type "whatever"
                   :label "true_positive"}]
                 (:body res)))
          (is (:disabled db-token))
          (is (= "ham@biscuit.co" to))
          (is (= "Deploy token found on GitHub" subject))
          (is (re-find #"'a token'" message))
          (is (re-find #"https://github.com/foo/bar" message))
          (is (re-find #"has been disabled" message))))

      (testing "when token is disabled"
        (let [token (db/add-deploy-token help/*db* "ham" "another token" nil nil false nil)
              token-str (:token token)
              db-token (find-token "ham" "another token")
              _ (db/disable-deploy-token help/*db* (:id db-token))
              _ (email/expect-mock-emails 1)
              res (app (build-breach-request token-str))
              _ (is (true? (email/wait-for-mock-emails)))
              [to subject message] (first @email/mock-emails)]
          (is (= 200 (:status res)))
          (is (= [{:token_raw token-str
                   :token_type "whatever"
                   :label "true_positive"}]
                 (:body res)))
          (is (= "ham@biscuit.co" to))
          (is (= "Deploy token found on GitHub" subject))
          (is (re-find #"'another token'" message))
          (is (re-find #"https://github.com/foo/bar" message))
          (is (re-find #"was already disabled" message))))

      (testing "with existing and non-existent tokens"
        (let [token (db/add-deploy-token help/*db* "ham" "a token" nil nil false nil)
              token-str (:token token)
              res (app (build-breach-request token-str "non-existent-token"))
              db-token (find-token "ham" "a token")]
          (is (= 200 (:status res)))
          (is (= [{:token_raw token-str
                   :token_type "whatever"
                   :label "true_positive"}
                  {:token_raw "non-existent-token"
                   :token_type "whatever"
                   :label "false_positive"}]
                 (:body res)))
          (is (:disabled db-token)))))))
