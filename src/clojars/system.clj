(ns clojars.system
  (:require
   [clojars.email :refer [simple-mailer]]
   [clojars.event :as event]
   [clojars.notifications :as notifications]
   ;; for defmethods
   [clojars.notifications.admin]
   [clojars.notifications.deploys]
   [clojars.notifications.group]
   [clojars.notifications.token]
   [clojars.notifications.user]
   [clojars.oauth.github :as github]
   [clojars.oauth.gitlab :as gitlab]
   [clojars.remote-service :as remote-service]
   [clojars.ring-servlet-patch :as patch]
   [clojars.s3 :as s3]
   [clojars.search :as search]
   [clojars.stats :refer [artifact-stats]]
   [clojars.storage :as storage]
   [clojars.web :as web]
   [clojars.web.repo-listing :as repo-listing]
   [com.stuartsierra.component :as component]
   [duct.component.endpoint :refer [endpoint-component]]
   [duct.component.handler :refer [handler-component]]
   [duct.component.hikaricp :refer [hikaricp]]
   [meta-merge.core :refer [meta-merge]]
   [ring.component.jetty :refer [jetty-server]]))

(def base-env
  {:app {:middleware []}
   :http {:configurator patch/use-status-message-header}})

(defrecord StorageComponent [error-reporter delegate on-disk-repo repo-bucket cdn-token cdn-url]
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
             :delegate (storage/full-storage error-reporter on-disk-repo repo-bucket
                                             cdn-token cdn-url))))
  (stop [t]
    (assoc t :delegate nil)))

(defn storage-component [on-disk-repo cdn-token cdn-url]
  (map->StorageComponent {:on-disk-repo on-disk-repo
                          :cdn-token cdn-token
                          :cdn-url cdn-url}))

(defn base-system
  "Enough of the system for use in scripts."
  [config]
  (-> (component/system-map
       :db            (hikaricp (assoc (:db config) :max-pool-size 20))
       :search        (search/lucene-component)
       :index-factory #(search/disk-index (:index-path config))
       :stats         (artifact-stats)
       :stats-bucket  (s3/s3-client (get-in config [:s3 :stats-bucket])))
      (component/system-using
       {:search [:index-factory :stats]
        :stats  [:stats-bucket]})))

(defn new-system [config]
  (let [{:as config :keys [github-oauth gitlab-oauth]} (meta-merge base-env config)]
    (-> (merge
         (base-system config)
         (component/system-map
          :app            (handler-component (:app config))
          :clojars-app    (endpoint-component web/clojars-app)
          :github         (github/new-github-service (:client-id github-oauth)
                                                     (:client-secret github-oauth)
                                                     (:callback-uri github-oauth))
          :gitlab         (gitlab/new-gitlab-service (:client-id gitlab-oauth)
                                                     (:client-secret gitlab-oauth)
                                                     (:callback-uri gitlab-oauth))
          :event-emitter  (event/new-sqs-emitter (:event-queue config))
          :event-receiver (event/new-sqs-receiver (:event-queue config))
          :http           (jetty-server (:http config))
          :http-client    (remote-service/new-http-remote-service)
          :mailer         (simple-mailer (:mail config))
          :notifications  (notifications/notification-component)
          :repo-bucket    (s3/s3-client (get-in config [:s3 :repo-bucket]))
          :repo-lister    (repo-listing/repo-lister (:cache-path config))
          :storage        (storage-component (:repo config) (:cdn-token config) (:cdn-url config))))
        (component/system-using
         {:app            [:clojars-app]
          :clojars-app    [:db :github :gitlab :error-reporter :event-emitter :http-client
                           :mailer :repo-lister :stats :search :storage]
          :event-emitter  [:error-reporter]
          :http           [:app]
          :notifications  [:db :mailer]
          :repo-lister    [:repo-bucket]
          :event-receiver [:error-reporter]
          :storage        [:error-reporter :repo-bucket]}))))
