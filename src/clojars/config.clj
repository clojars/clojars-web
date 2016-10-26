(ns clojars.config
  (:require [clojure.tools.cli :refer [cli]]
            [clojure.java.io :as io]
            [aero.core :as aero]
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

(def default-config
  (delay
    (if-let [cfg (io/resource "default_config.edn")]
      (aero/read-config cfg)
      (println "WARNING: failed to find default_config.edn"))))

;; we attempt to read a file defined on clojars.config.file property at load time
;; this is handy for interactive development and unit tests
(def config
  (delay
    (-> @default-config
      (merge (when-let [extra-config (System/getProperty "clojars.config.file")]
               (aero/read-config extra-config)))
      (update :mail parse-mail))))

(defn parse-args [args]
  (cli args
       ["-h" "--help" "Show this help text and exit" :flag true]))

(defn remove-nil-vals [m]
  (into {} (remove #(nil? (val %)) m)))

(defn parse-config [args]
  (let [[arg-opts args banner] (parse-args args)
        arg-opts (remove-nil-vals arg-opts)]
    [arg-opts args banner]))

(defn configure [args]
  (let [[options args banner] (parse-config args)]
    (when (:help options)
      (println "clojars-web: a jar repository webapp written in Clojure")
      (println "             https://github.com/clojars/clojars-web")
      (println)
      (println banner)
      (println "The config file must be a Clojure map: {:port 8080 :repo \"/var/repo\"}")
      (println "The :db and :mail options can be maps instead of URLs.")
      (System/exit 0))))
