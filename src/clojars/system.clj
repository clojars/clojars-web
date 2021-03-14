(ns clojars.system
  (:require
   [clojars.email :refer [simple-mailer]]
   [clojars.notifications :as notifications]
   ;; for defmethods
   [clojars.notifications.mfa]
   [clojars.notifications.token]
   [clojars.oauth.github :as github]
   [clojars.oauth.gitlab :as gitlab]
   [clojars.remote-service :as remote-service]
   [clojars.ring-servlet-patch :as patch]
   [clojars.s3 :as s3]
   [clojars.search :refer [lucene-component]]
   [clojars.stats :refer [artifact-stats]]
   [clojars.storage :as storage]
   [clojars.web :as web]
   [clucy.core :as clucy]
   [com.stuartsierra.component :as component]
   [duct.component.endpoint :refer [endpoint-component]]
   [duct.component.handler :refer [handler-component]]
   [duct.component.hikaricp :refer [hikaricp]]
   [meta-merge.core :refer [meta-merge]]
   [ring.component.jetty :refer [jetty-server]]))

(def base-env
  {:app {:middleware []}
   :http {:configurator patch/use-status-message-header}})

(defrecord StorageComponent [delegate on-disk-repo repo-bucket cdn-token cdn-url]
  storage/Storage
  (-write-artifact [_ path file force-overwrite?]
    (storage/write-artifact delegate path file force-overwrite?))
  (remove-path [_ path]
    (storage/remove-path delegate path))
  (path-exists? [_ path]
    (storage/path-exists? delegate path))
  (path-seq [_ path]
    (storage/path-seq delegate path))
  (artifact-url [_ path]
    (storage/artifact-url delegate path))

  component/Lifecycle
  (start [t]
    (if delegate
      t
      (assoc t
             :delegate (storage/full-storage on-disk-repo repo-bucket
                                             cdn-token cdn-url))))
  (stop [t]
    (assoc t :delegate nil)))

(defn storage-component [on-disk-repo cdn-token cdn-url]
  (map->StorageComponent {:on-disk-repo on-disk-repo
                          :cdn-token cdn-token
                          :cdn-url cdn-url}))

(defn s3-bucket
  [{:keys [access-key-id secret-access-key region] :as cfg} bucket-key]
  (s3/s3-client access-key-id secret-access-key region (get cfg bucket-key)))

(defn new-system [config]
  (let [{:as config :keys [github-oauth gitlab-oauth]} (meta-merge base-env config)]
    (-> (component/system-map
         :app               (handler-component (:app config))
         :clojars-app       (endpoint-component web/clojars-app)
         :db                (hikaricp (:db config))
         :github            (github/new-github-service (:client-id github-oauth)
                                                       (:client-secret github-oauth)
                                                       (:callback-uri github-oauth))
         :gitlab            (gitlab/new-gitlab-service (:client-id gitlab-oauth)
                                                       (:client-secret gitlab-oauth)
                                                       (:callback-uri gitlab-oauth))
         :http              (jetty-server (:http config))
         :http-client       (remote-service/new-http-remote-service)
         :index-factory     #(clucy/disk-index (:index-path config))
         :mailer            (simple-mailer (:mail config))
         :notifications     (notifications/notification-component)
         :repo-bucket       (s3-bucket (:s3 config) :repo-bucket)
         :search            (lucene-component)
         :stats             (artifact-stats)
         :stats-bucket      (s3-bucket (:s3 config) :stats-bucket)
         :storage           (storage-component (:repo config) (:cdn-token config) (:cdn-url config)))
        (component/system-using
         {:app               [:clojars-app]
          :clojars-app       [:db :github :gitlab :error-reporter :http-client
                              :mailer :stats :search :storage]
          :http              [:app]
          :notifications     [:db :mailer]
          :search            [:index-factory :stats]
          :stats             [:stats-bucket]
          :storage           [:repo-bucket]}))))
