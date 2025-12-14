(ns clojars.test-helper
  (:require
   [cemerick.pomegranate.aether :as aether]
   [clojars.config :as config]
   [clojars.db :as db]
   [clojars.db.migrate :as migrate]
   [clojars.dev.setup :as setup]
   [clojars.email :as email]
   [clojars.errors :as errors]
   [clojars.oauth.service :as oauth-service]
   [clojars.s3 :as s3]
   [clojars.search :as search]
   [clojars.stats :as stats]
   [clojars.system :as system]
   [clojars.web :as web]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [is]]
   [cognitect.aws.client.api :as aws]
   [com.stuartsierra.component :as component]
   [matcher-combinators.test])
  (:import
   (java.io
    File)
   (org.apache.lucene.store
    ByteBuffersDirectory)
   (org.apache.maven.wagon.providers.http
    HttpWagon)))

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

;; We use non-secure http repos in tests, but pomegranate throws if the repo
;; isn't secure without a wagon registered.
(defn register-http-wagon! []
  (aether/register-wagon-factory! "http" #(HttpWagon.)))

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

(def clear-database setup/clear-database!)

(defn with-clean-database [f]
  (binding [config/*profile* "test"]
    ;; double binding since ^ needs to be bound for config to load
    ;; properly
    (binding [*db* (:db (config/config))]
      (clear-database *db*)
      (with-out-str
        (migrate/migrate *db*))
      (f))))

(defrecord NoStats []
  stats/Stats
  (download-count [_t _group-id _artifact-id] 0)
  (download-count [_t _group-id _artifact-id _version] 0)
  (total-downloads [_t] 0))

(defn no-stats []
  (->NoStats))

(defn no-search []
  (reify search/Search))

(declare ^:dynamic *s3-repo-bucket*)

(defn with-s3-repo-bucket [f]
  (binding [*s3-repo-bucket* (s3/mock-s3-client)]
    (f)))

(declare ^:dynamic *s3-stats-bucket*)

(defn with-s3-stats-bucket [f]
  (binding [*s3-stats-bucket* (s3/mock-s3-client)]
    (f)))

(declare ^:dynamic test-port)

(defn memory-index []
  (ByteBuffersDirectory.))

(declare ^:dynamic system)

(defn app []
  (web/clojars-app system))

(defn- with-test-system*
  [f]
  (binding [config/*profile* "test"]
    ;; double binding since ^ needs to be bound for config to load
    ;; properly
    (binding [system (component/start
                      (assoc (system/new-system (config/config))
                             :repo-bucket (s3/mock-s3-client)
                             :error-reporter (quiet-reporter)
                             :index-factory memory-index
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

(defn run-test-app
  [f]
  (with-test-system*
    #(let [server (get-in system [:http :server])
           port (-> server .getConnectors first .getLocalPort)]
       (binding [test-port port]
         (f)))))

(defn get-content-type [resp]
  (some-> resp :headers (get "content-type") (str/split #";") first))

(defn assert-cors-header
  [resp]
  (= "*" (get-in resp [:headers "access-control-allow-origin"])))

(defn rewrite-pom [file m]
  (let [new-pom (doto (File/createTempFile (.getName file) ".pom")
                  .deleteOnExit)]
    (spit new-pom
          (reduce (fn [accum [element new-value]]
                    (str/replace accum (re-pattern (format "<(%s)>.*?<" (name element)))
                                 (format "<$1>%s<" new-value)))
                  (slurp file)
                  m))
    new-pom))

(defn add-verified-group
  [account group]
  (db/add-group *db* account group)
  (db/verify-group! *db* account group))

(defmacro match-audit
  [params m]
  `(let [db# (:db (config/config))
         audit# (first (db/find-audit db# ~params))]
     (is (~'match? ~m audit#))))

(defmacro with-time
  [t & body]
  `(with-redefs [db/get-time (constantly ~t)]
     ~@body))

(defn TXT-vec->str
  [txt-records]
  (->> txt-records
       (map #(format "\"%s\"" %))
       (str/join "\n")))

(defmacro with-TXT
  [txt-records & body]
  `(with-redefs [shell/sh (constantly {:out  (TXT-vec->str ~txt-records)
                                       :exit 0})]
     ~@body))

(defn wait-for-s3-key
  [bucket key]
  (loop [attempt 0]
    (if (s3/object-exists? bucket key)
      true
      (if (< attempt 10)
        (do
          (Thread/sleep 1000)
          (recur (inc attempt)))
        false))))

(defn assert-status
  [session status]
  (is (= status (get-in session [:response :status]))))

(defn real-s3-client
  "This creates a real s3 client for testing s3-specific functionality. It
  requires minio to running. See docker-compose.yml."
  [bucket]
  (let [client (s3/s3-client bucket
                             {:credentials {:access-key-id     "fake-access-key"
                                            :secret-access-key "fake-secret-key"}
                              :endpoint {:protocol "http"
                                         :hostname "localhost"
                                         :port     9000}
                              :region "us-east-1"})]
    (aws/invoke (:s3 client) {:op      :CreateBucket
                              :request {:Bucket bucket}})
    client))
