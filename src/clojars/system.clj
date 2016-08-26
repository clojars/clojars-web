(ns clojars.system
  (:require [clojars
             [email :refer [simple-mailer]]
             [ring-servlet-patch :as patch]
             [search :refer [lucene-component]]
             [stats :refer [file-stats]]
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
         :clojars-app   (endpoint-component web/handler-optioned))
        (component/system-using
         {:http [:app]
          :app  [:clojars-app]
          :stats [:fs-factory]
          :search [:index-factory :stats]
          :clojars-app [:storage :db :error-reporter :stats :search :mailer]}))))
