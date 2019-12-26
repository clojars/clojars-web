(ns clojars.config
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn url-decode [s]
  (java.net.URLDecoder/decode s "UTF-8"))

(defn parse-query [query]
  (when query
   (reduce (fn [m entry]
             (let [[k v] (str/split entry #"=" 2)]
               (assoc m (keyword (url-decode k)) (url-decode v))))
           {} (str/split query #"&" 2))))

(defn parse-mail-uri [x]
  (let [uri (java.net.URI. x)]
    (merge
     {:ssl (= (.getScheme uri) "smtps")
      :hostname (.getHost uri)}
     (when (pos? (.getPort uri))
       {:port (.getPort uri)})
     (when-let [user-info (.getUserInfo uri)]
       (let [[user pass] (str/split user-info #":" 2)]
         {:username user
          :password pass}))
     (parse-query (.getQuery uri)))))

(defn parse-mail [x]
  (if (string? x)
    (parse-mail-uri x)
    x))


;; we attempt to read a file defined on clojars.config.file property at load time
;; this is handy for interactive development and unit tests
(defn merge-extra-config
  [default-config]
  (-> default-config
      (merge (when-let [extra-config (System/getProperty "clojars.config.file")]
               (aero/read-config extra-config)))
      (update :mail parse-mail)))

(defonce config (atom {}))

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
        (assoc-in [:db :uri]  (jdbc-url db)))))

(defn load-config
  [profile]
  (let [cfg (-> (io/resource "default_config.edn")
                (aero/read-config {:profile profile})
                (merge-extra-config)
                (translate))]
    (reset! config cfg)))
