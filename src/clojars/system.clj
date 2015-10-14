(ns clojars.system
  (:require [clojars
             [ring-servlet-patch :as patch]
             [web :as web]]
            [com.stuartsierra.component :as component]
            [duct.component
             [endpoint :refer [endpoint-component]]
             [handler :refer [handler-component]]
             [hikaricp :refer [hikaricp]]]
            [meta-merge.core :refer [meta-merge]]
            [ring.component.jetty :refer [jetty-server]]))

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
         :app  (handler-component (:app config))
         :http (jetty-server (:http config))
         :db   (hikaricp (:db config))
         :clojars-app   (endpoint-component web/handler-optioned))
        (component/system-using
         {:http [:app]
          :app  [:clojars-app]
          :clojars-app [:db]}))))
