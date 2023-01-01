(ns clojars.config
  (:require
   [aero.core :as aero]
   [clojure.java.io :as io]
   [cognitect.aws.client.api :as aws]))

(defn- ssm-client
  []
  (aws/client {:api :ssm}))

(defn- throw-on-error
  [v]
  (if (some? (:cognitect.anomalies/category v))
    (throw (ex-info "SSM request failed" v))
    v))

(defn- get-parameter-value
  [param-name]
  (->> {:op :GetParameter
        :request {:Name param-name
                  :WithDecryption true}}
       (aws/invoke (ssm-client))
       (throw-on-error)
       (:Parameter)
       (:Value)))

(def ^:dynamic *profile* "development")

(defmethod aero/reader 'ssm-parameter
  [_opts _tag value]
  (if (= :production *profile*)
    (get-parameter-value value)
    ""))

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
  (-> (io/resource "config.edn")
      (aero/read-config {:profile profile})
      (translate)))

(def config*
  (memoize load-config))

(defn config
  "Loads the config based on the profile that is provided by one
  of (tried in order, and one of: \"development\", \"production\", or
  \"test\"):
  - CLOJARS_ENVIRONMENT environment variable
  - *profile* dynamic var (defaults to \"development\")"
  []
  (let [profile (keyword (or (System/getenv "CLOJARS_ENVIRONMENT")
                             *profile*))]
    (binding [*profile* profile]
      (config* profile))))
