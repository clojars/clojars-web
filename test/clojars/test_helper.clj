(ns clojars.test-helper
  (:require [clojars
             [cloudfiles :as cf]
             [config :as config]
             [errors :as errors]
             [stats :as stats]
             [search :as search]
             [system :as system]
             [web :as web]]
            [clojars.db.migrate :as migrate]
            [clojars.storage :as storage]
            [clojure.java
             [io :as io]
             [jdbc :as jdbc]]
            [clojure.string :as str]
            [clucy.core :as clucy]
            [com.stuartsierra.component :as component])
  (:import
   (java.io File)
   (java.time ZonedDateTime)
   (java.util Date)))

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
                         "delete from users")
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

(defn transient-cloudfiles []
  (cf/connect "" "" "test-repo" "transient"))

(declare ^:dynamic *cloudfiles*)

(defn with-cloudfiles [f]
  (binding [*cloudfiles* (transient-cloudfiles)]
    (f)))

(declare ^:dynamic test-port)

(defn app
  ([] (app {}))
  ([{:keys [storage db error-reporter stats search mailer]
     :or {db *db*
          storage (storage/fs-storage (:repo (config/config)))
          error-reporter (quiet-reporter)
          stats (no-stats)
          search (no-search)
          mailer nil}}]
   (web/clojars-app storage db error-reporter stats search mailer)))

(declare ^:dynamic system)

(defn app-from-system []
  ;; TODO once the database is a protocol, review
  ;; usage of this to move things into unit tests
  (web/handler-optioned system))

(defn run-test-app
  ([f]
   (binding [config/*profile* "test"]
     ;; double binding since ^ needs to be bound for config to load
     ;; properly
     (binding [system (component/start (assoc (system/new-system (config/config))
                                            :cloudfiles (transient-cloudfiles)
                                            :error-reporter (quiet-reporter)
                                            :index-factory #(clucy/memory-index)
                                            :stats (no-stats)))]
       (let [server (get-in system [:http :server])
             port (-> server .getConnectors first .getLocalPort)
             db (get-in system [:db :spec])]
         (binding [test-port port]
           (try
             (clear-database db)
             (with-out-str
               (migrate/migrate db))
             (f)
             (finally
               (component/stop system)))))))))

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
