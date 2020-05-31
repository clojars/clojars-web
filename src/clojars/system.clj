(ns clojars.system
  (:require
   [clojars.email :refer [simple-mailer]]
   [clojars.notifications.mfa :as mfa]
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
  (let [config (meta-merge base-env config)]
    (-> (component/system-map
         :app               (handler-component (:app config))
         :http              (jetty-server (:http config))
         :db                (hikaricp (:db config))
         :stats-bucket      (s3-bucket (:s3 config) :stats-bucket)
         :repo-bucket       (s3-bucket (:s3 config) :repo-bucket)
         :stats             (artifact-stats)
         :index-factory     #(clucy/disk-index (:index-path config))
         :search            (lucene-component)
         :mailer            (simple-mailer (:mail config))
         :mfa-notifications (mfa/notification-component)
         :storage           (storage-component (:repo config) (:cdn-token config) (:cdn-url config))
         :clojars-app       (endpoint-component web/handler-optioned))
        (component/system-using
         {:http              [:app]
          :app               [:clojars-app]
          :mfa-notifications [:db :mailer]
          :stats             [:stats-bucket]
          :search            [:index-factory :stats]
          :storage           [:repo-bucket]
          :clojars-app       [:storage :db :error-reporter :stats :search :mailer]}))))
