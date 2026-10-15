(ns clojars.models.deploy-token
  (:require
   [buddy.core.codecs :as buddy.codecs]
   [buddy.core.hash :as buddy.hash]
   [cemerick.friend.credentials :as creds]
   [clojars.db :as db]
   [clojars.models.user :as user-m]
   [clojars.util :refer [filter-some]]
   [next.jdbc.sql :as sql]
   [next.jdbc.types :as jdbc.types]))

(defn find-user-tokens-by-username
  [db username]
  (db/q db
        {:select :*
         :from :deploy_tokens
         :where [:= :user_id {:select :id
                              :from :users
                              :where [:= :user username]
                              :limit 1}]
         :order-by [[:last_used :desc]
                    [:disabled :desc]
                    [:created :desc]]}))

(defn find-token
  [db token-id]
  (first
   (db/q db
         {:select :*
          :from :deploy_tokens
          :where [:= :id token-id]
          :limit 1})))

(def hash-deploy-token
  (comp buddy.codecs/bytes->hex buddy.hash/sha256))

(defn find-token-by-value
  "Finds a token with the matching value. This is somewhat expensive,
  since it looks up the token by hash, then brcypt-verifies each result."
  [db token-value]
  (filter-some
   #(creds/bcrypt-verify token-value (:token %))
   (db/q db
         {:select :*
          :from :deploy_tokens
          :where [:= :token_hash (hash-deploy-token token-value)]})))

(defn generate-deploy-token []
  (str "CLOJARS_" (db/generate-secure-hex-token 30)))

(defn is-deploy-token?
  "Returns true if the value is token shaped."
  [v]
  (boolean (and v (re-find #"^CLOJARS_[0-9a-f]{60}$" v))))

(defn add-deploy-token
  [db username token-name group-name jar-name single-use? expires-at]
  (let [user (user-m/find-user db username)
        token (generate-deploy-token)
        record {:user_id (:id user)
                :name token-name
                :token token
                :token_hash (hash-deploy-token token)
                :group_name group-name
                :jar_name jar-name
                :single_use (jdbc.types/as-other (if single-use?
                                                   "yes"
                                                   "no"))
                :expires_at expires-at}]
    (sql/insert! db :deploy_tokens
                 (update record :token db/bcrypt))
    record))

(defn find-deploy-tokens-for-user
  [db user-id]
  (mapv #(dissoc % :token)
        (db/q db
              {:select :*
               :from :deploy_tokens
               :where [:= :user_id user-id]})))

(defn consume-deploy-token
  [db token-id]
  (sql/update! db :deploy_tokens
               {:single_use (jdbc.types/as-other "used")
                :updated    (db/get-time)}
               {:id token-id}))

(defn disable-deploy-token
  [db token-id]
  (sql/update! db :deploy_tokens
               {:disabled true
                :updated  (db/get-time)}
               {:id token-id}))

(defn disable-deploy-tokens-for-user
  [db user-id]
  (sql/update! db :deploy_tokens
               {:disabled true
                :updated  (db/get-time)}
               {:user_id user-id}))

(defn set-deploy-token-used
  [db token-id]
  (sql/update! db :deploy_tokens
               {:last_used (db/get-time)}
               {:id token-id}))

(defn set-deploy-token-hash
  [db token-id token-value]
  (sql/update! db :deploy_tokens
               {:token_hash (hash-deploy-token token-value)}
               {:id token-id
                :token_hash nil}))
