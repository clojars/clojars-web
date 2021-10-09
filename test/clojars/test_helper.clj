(ns clojars.test-helper
  (:require
   [clojars.config :as config]
   [clojars.db :as db]
   [clojars.db.migrate :as migrate]
   [clojars.email :as email]
   [clojars.errors :as errors]
   [clojars.oauth.service :as oauth-service]
   [clojars.remote-service :as remote-service]
   [clojars.s3 :as s3]
   [clojars.search :as search]
   [clojars.stats :as stats]
   [clojars.storage :as storage]
   [clojars.system :as system]
   [clojars.web :as web]
   [clojure.java.io :as io]
   [clojure.java.jdbc :as jdbc]
   [clojure.string :as str]
   [clojure.test :refer [is]]
   [clucy.core :as clucy]
   [com.stuartsierra.component :as component]
   [matcher-combinators.test])
  (:import
   (java.io
    File)
   (java.time
    ZonedDateTime)
   (java.util
    Date)))

(def tmp-dir (io/file (System/getProperty "java.io.tmpdir")))
(def local-repo (io/file tmp-dir "clojars" "test" "local-repo"))
(def local-repo2 (io/file tmp-dir "clojars" "test" "local-repo2"))

(defn delete-file-recursively
  "Delete file f. If it's a directory, recursively delete all its contents."
  [f]
  (let [f (io/file f)]
    (when (.exists f)
      (when (.isDirectory f)
        (doseq [child (.listFiles f)]
          (delete-file-recursively child)))
      (io/delete-file f))))

(defn with-local-repos
  [f]
  (delete-file-recursively local-repo)
  (delete-file-recursively local-repo2)
  (f))

(defn default-fixture [f]
  (binding [config/*profile* "test"]
    (let [cleanup (fn [] (run!
                          #(delete-file-recursively (io/file ((config/config) %)))
                          [:deletion-backup-dir :repo]))]
      (cleanup)
      (f)
      (cleanup))))

(defn quiet-reporter []
  (reify errors/ErrorReporter
    (-report-error [t e ex id]
      (println t e ex id))))

(declare ^:dynamic *db*)

(defn clear-database [db]
  (try
    (jdbc/db-do-commands db
                         "delete from deps"
                         "delete from groups"
                         "delete from jars"
                         "delete from users"
                         "delete from group_verifications"
                         "delete from audit")
    (catch Exception _)))

(defn with-clean-database [f]
  (binding [config/*profile* "test"]
    ;; double binding since ^ needs to be bound for config to load
    ;; properly
    (binding [*db* {:connection (jdbc/get-connection (:db (config/config)))}]
      (try
        (clear-database *db*)
        (with-out-str
          (migrate/migrate *db*))
        (f)
        (finally (.close (:connection *db*)))))))

(defrecord NoStats []
  stats/Stats
  (download-count [t group-id artifact-id] 0)
  (download-count [t group-id artifact-id version] 0)
  (total-downloads [t] 0))

(defn no-stats []
  (->NoStats))

(defn no-search []
  (reify search/Search))

(declare ^:dynamic *s3-repo-bucket*)

(defn with-s3-repo-bucket [f]
  (binding [*s3-repo-bucket* (s3/mock-s3-client)]
    (f)))

(declare ^:dynamic test-port)

(defn app
  ([] (app {}))
  ([{:keys [storage db error-reporter http-client stats search mailer github]
     :or {db {:spec *db*}
          storage (storage/fs-storage (:repo (config/config)))
          error-reporter (quiet-reporter)
          http-client (remote-service/new-mock-remote-service)
          stats (no-stats)
          search (no-search)
          mailer nil}}]
   (web/clojars-app
    {:db db
     :error-reporter error-reporter
     :http-client http-client
     :github github
     :mailer mailer
     :search search
     :stats stats
     :storage storage})))

(declare ^:dynamic system)

(defn app-from-system []
  (web/clojars-app system))

(defn with-test-system*
  [f]
  (binding [config/*profile* "test"]
    ;; double binding since ^ needs to be bound for config to load
    ;; properly
    (binding [system (component/start (assoc (system/new-system (config/config))
                                             :repo-bucket (s3/mock-s3-client)
                                             :error-reporter (quiet-reporter)
                                             :index-factory #(clucy/memory-index)
                                             :mailer (email/mock-mailer)
                                             :stats (no-stats)
                                             :github (oauth-service/new-mock-oauth-service "GitHub" {})))]
      (let [db (get-in system [:db :spec])]
        (try
          (clear-database db)
          (with-out-str
            (migrate/migrate db))
          (f)
          (finally
            (component/stop system)))))))

(defmacro with-test-system
  [& body]
  `(with-test-system* #(do ~@body)))

(defn run-test-app
  [f]
  (with-test-system*
    #(let [server (get-in system [:http :server])
           port (-> server .getConnectors first .getLocalPort)]
       (binding [test-port port]
         (f)))))

(defn get-content-type [resp]
  (some-> resp :headers (get "content-type") (str/split #";") first))

(defn assert-cors-header [resp]
  (some-> resp :headers
          (get "access-control-allow-origin")
          (= "*")))

(defn rewrite-pom [file m]
  (let [new-pom (doto (File/createTempFile (.getName file) ".pom")
                  .deleteOnExit)]
    (-> file
        slurp
        (as-> % (reduce (fn [accum [element new-value]]
                          (str/replace accum (re-pattern (format "<(%s)>.*?<" (name element)))
                                       (format "<$1>%s<" new-value)))
                        %
                        m))
        (->> (spit new-pom)))
    new-pom))

(defn date-from-iso-8601-str
  [iso-8601-date-string]
  (-> (ZonedDateTime/parse iso-8601-date-string)
      .toInstant
      (Date/from)))

(defn at-as-time-str
  "Adjusts the :at (or :created) Date to a millis-since-epoch string to
  match the search results."
  [data]
  (let [date->time-str #(str (.getTime %))]
    (cond-> data
      (:at data)      (update :at date->time-str)
      (:created data) (update :created date->time-str))))

(defn add-verified-group
  [account group]
  (db/add-group *db* account group)
  (db/verify-group! *db* account group))

(defmacro match-audit
  [params m]
  `(let [db# (:db (config/config))
         audit# (first (db/find-audit db# ~params))]
     (prn (db/find-audit db# ~params))
     (is (~'match? ~m audit#))))
