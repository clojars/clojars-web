(ns clojars.config
  (:require [clojure.tools.cli :refer [cli]]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def default-config
  {:port 8080
   :bind "127.0.0.1"
   :db {:classname "org.sqlite.JDBC"
        :subprotocol "sqlite"
        :subname "data/db"}
   :base-url "https://clojars.org"
   :stats-dir "data/stats"
   :queue-storage-dir "data/durable-queue-slabs"
   :index-path "data/index"
   :nrepl-port 7991
   :mail {:hostname "127.0.0.1"
          :ssl false
          :from "noreply@clojars.org"}
   :bcrypt-work-factor 12
   :yeller-environment "development"})

(defn parse-resource [f]
  (when-let [r (io/resource f)] (read-string (slurp r))))

;; we attempt to read a config.clj from the classpath at load time
;; this is handy for interactive development and unit tests
(def config (merge default-config (parse-resource "config.clj")))

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
   (if (= (first x) \{)
     (read-string x)
     (parse-mail-uri x))
   x))

(def env-vars
  [["CONFIG_FILE" :config-file]
   ["PORT" :port #(Integer/parseInt %)]
   ["BIND" :bind]
   ["DATABASE_URL" :db]
   ["MAIL_URL" :mail parse-mail-uri]
   ["REPO" :repo]
   ["DELETION_BACKUP_DIR" :deletion-backup-dir]
   ["NREPL_PORT" :nrepl-port #(Integer/parseInt %)]
   ["YELLER_ENV" :yeller-environment]
   ["YELLER_TOKEN" :yeller-token]
   ["CLOUDFILES_USER" :cloudfiles-user]
   ["CLOUDFILES_TOKEN" :cloudfiles-token]
   ["CLOUDFILES_CONTAINER" :cloudfiles-container]])

(defn parse-env []
  (reduce
   (fn [m [var k & [f]]]
     (if-let [x (System/getenv var)]
       (assoc m k ((or f identity) x))
       m))
   {} env-vars))

(defn parse-args [args defaults]
  (cli args
       ["-h" "--help" "Show this help text and exit" :flag true]
       ["-f" "Read configuration map from a file" :name :config-file]
       ["-p" "--port" "Port to listen on for web requests"
        :parse-fn #(Integer/parseInt %) :default (:port defaults)]
       ["-b" "--bind" "Address to bind to for web requests"
        :default (:address defaults)]
       ["--db" "Database URL like sqlite:data/db"]
       ["--mail" "SMTP URL like smtps://user:pass@host:port?from=me@example.org"]
       ["--repo" "Path to store jar files in"]
       ["--bcrypt-work-factor" "Difficulty factor for bcrypt password hashing"
        :parse-fn #(Integer/parseInt %) :default (:bcrypt-work-factor defaults)]))

(defn parse-file [f]
  (read-string (slurp (io/file f))))

(defn remove-nil-vals [m]
  (into {} (remove #(nil? (val %)) m)))

(defn parse-config [args]
  (let [env-opts (merge default-config (parse-resource "config.clj") (parse-env))
        [arg-opts args banner] (parse-args args env-opts)
        arg-opts (remove-nil-vals arg-opts)
        opts (if-let [f (or (:config-file arg-opts) (:config-file env-opts))]
               (merge env-opts (parse-file f) arg-opts)
               (merge env-opts arg-opts))]
    [opts args banner]))

(defn configure [args]
  (let [[options args banner] (parse-config args)]
    (when (:help options)
      (println "clojars-web: a jar repository webapp written in Clojure")
      (println "             https://github.com/clojars/clojars-web")
      (println)
      (println banner)
      (println "The config file must be a Clojure map: {:port 8080 :repo \"/var/repo\"}")
      (println "The :db and :mail options can be maps instead of URLs.")
      (println)
      (println "Some options can be set using these environment variables:")
      (println (str/join " " (map first env-vars)))
      (System/exit 0))
    (alter-var-root #'config (fn [_] options))))
