(ns clojars.config
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [meta-merge.core :refer [meta-merge]]))

(defn get-extra-config-path
  []
  (System/getenv "CLOJARS_EXTRA_CONFIG"))

;; We attempt to read a file defined by the CLOJARS_EXTRA_CONFIG env
;; var at load time. This is used to load production configuration.
(defn merge-extra-config
  [default-config]
  (meta-merge
    default-config
    (when-let [extra-config (get-extra-config-path)]
      (aero/read-config extra-config))))

(defn jdbc-url [db-config]
  (if (string? db-config)
    (if (.startsWith db-config "jdbc:")
      db-config
      (str "jdbc:" db-config))
    (let [{:keys [dbtype dbname host port user password]} db-config]
      (format "jdbc:%s://%s:%s/%s?user=%s&password=%s"
              dbtype host port dbname user password))))

(defn translate [config]
  (let [{:keys [port bind db]} config]
    (-> config
        (assoc :http {:port port :host bind})
        (assoc-in [:db :uri] (jdbc-url db)))))

(defn- load-config
  [profile]
  (-> (io/resource "default_config.edn")
      (aero/read-config {:profile profile})
      (merge-extra-config)
      (translate)))

(def ^:dynamic *profile* "development")

(def config*
  (memoize load-config))

(defn config
  "Loads the config based on the profile that is provided by one
  of (tried in order, and one of: \"development\", \"production\", or
  \"test\"):
  - CLOJARS_ENVIRONMENT environment variable
  - *profile* dynamic var (defaults to \"development\")"
  []
  (let [env (keyword (or (System/getenv "CLOJARS_ENVIRONMENT")
                         *profile*))]
    (config* env)))
