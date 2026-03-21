(ns clojars.http-kit
  (:require
   [com.stuartsierra.component :as component]
   [org.httpkit.server :as http]))

(defrecord HttpKitServer [config]
  component/Lifecycle
  (start [this]
    (let [{:keys [host port]} config
          server (http/run-server
                  (-> this :app :handler)
                  {:ip                   host
                   :port                 port
                   :legacy-return-value? false
                   ;; must be >= the highest client_max_body_size in
                   ;; infrastructure/aws-ansible/roles/clojars/templates/clojars.nginx.conf.j2
                   ;; 100 Mb
                   :max-body             (* 100 1024 1024)})]
      (assoc this :server server)))
  (stop [this]
    (when-some [server (:server this)]
      (http/server-stop! server {:timeout 1000}))))

(defn get-port
  [c]
  (when-some [server (:server c)]
    (http/server-port server)))

(defn new-server
  [config]
  (->HttpKitServer config))
