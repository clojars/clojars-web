(ns clojars.system
  (:require [clojars
             [email :refer [simple-mailer]]
             [ring-servlet-patch :as patch]
             [queue :refer [queue-component]]
             [search :refer [lucene-component]]
             [stats :refer [file-stats]]
             [storage :as storage]
             [web :as web]]
            [clucy.core :as clucy]
            [com.stuartsierra.component :as component]
            [duct.component
             [endpoint :refer [endpoint-component]]
             [handler :refer [handler-component]]
             [hikaricp :refer [hikaricp]]]
            [meta-merge.core :refer [meta-merge]]
            [ring.component.jetty :refer [jetty-server]])
  (:import java.nio.file.FileSystems))

(def base-env
  {:app {:middleware []}
   :http {:configurator patch/use-status-message-header}})

(defn jdbc-url [db-config]
  (if (string? db-config)
    (if (.startsWith db-config "jdbc:")
      db-config
      (str "jdbc:" db-config))
    (let [{:keys [subprotocol subname]} db-config]
      (format "jdbc:%s:%s" subprotocol subname))))

(defn translate [config]
  (let [{:keys [port bind db]} config]
    (assoc config
           :http {:port port :host bind}
           :db {:uri (jdbc-url db)})))

(defrecord StorageComponent [delegate on-disk-repo cloudfiles queue]
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
        :delegate (storage/full-storage on-disk-repo cloudfiles queue))))
  (stop [t]
    (assoc t :delegate nil)))

(defn storage-component [on-disk-repo]
  (map->StorageComponent {:on-disk-repo on-disk-repo}))

(defn new-system [config]
  (let [config (meta-merge base-env (translate config))]
    (-> (component/system-map
         :app           (handler-component (:app config))
         :http          (jetty-server (:http config))
         :db            (hikaricp (:db config))
         :fs-factory    #(FileSystems/getDefault)
         :stats         (file-stats (:stats-dir config))
         :index-factory #(clucy/disk-index (:index-path config))
         :search        (lucene-component)
         :mailer        (simple-mailer (:mail config))
         :queue         (queue-component (:queue-storage-dir config))
         :storage       (storage-component (:repo config))
         :clojars-app   (endpoint-component web/handler-optioned))
        (component/system-using
         {:http [:app]
          :app  [:clojars-app]
          :queue [:error-reporter]
          :stats [:fs-factory]
          :search [:index-factory :stats]
          :storage [:cloudfiles :queue]
          :clojars-app [:storage :db :error-reporter :stats :search :mailer]}))))
