(ns clojars.test.test-helper
  (:import java.io.File)
  (:require [clojars
             [config :refer [config]]
             [db :as db]
             [errors :as errors]
             [search :as search]
             [system :as system]
             [web :as web]]
            [clojars.components.serial-sqlite :as sqlite]
            [clojars.db.migrate :as migrate]
            [clojure.java
             [io :as io]
             [jdbc :as jdbc]]
            [clucy.core :as clucy]
            [com.stuartsierra.component :as component]))

(def local-repo (io/file (System/getProperty "java.io.tmpdir")
                         "clojars" "test" "local-repo"))
(def local-repo2 (io/file (System/getProperty "java.io.tmpdir")
                         "clojars" "test" "local-repo2"))

(def test-config {:port 0
                  :bind "127.0.0.1"
                  :db {:classname "org.sqlite.JDBC"
                       :subprotocol "sqlite"
                       :subname ":memory:"}
                  :repo "data/test/repo"
                  :stats-dir "data/test/stats"
                  :index-path "data/test/index"
                  :bcrypt-work-factor 12
                  :mail {:hostname "smtp.gmail.com"
                         :from "noreply@clojars.org"
                         :username "clojars@pupeno.com"
                         :password "fuuuuuu"
                         :port 465 ; If you change ssl to false, the port might not be effective, search for .setSSL and .setSslSmtpPort
                         :ssl true}})

(defn using-test-config [f]
  (with-redefs [config test-config]
    (f)))

(defn delete-file-recursively
  "Delete file f. If it's a directory, recursively delete all its contents."
  [f]
  (let [f (io/file f)]
    (when (.exists f)
      (when (.isDirectory f)
        (doseq [child (.listFiles f)]
          (delete-file-recursively child)))
      (io/delete-file f))))

(defn make-download-count! [m]
  (spit (io/file (config :stats-dir) "all.edn")
        (pr-str m)))

(defn make-index! [v]
  (delete-file-recursively (io/file (config :index-path)))
  (with-open [index (clucy/disk-index (config :index-path))]
    (if (empty? v)
      (do (clucy/add index {:dummy true})
          (clucy/search-and-delete index "dummy:true"))
      (binding [clucy/*analyzer* search/analyzer]
        (doseq [a v]
          (clucy/add index a))))))

(defn default-fixture [f]
  (using-test-config
   (fn []
     (delete-file-recursively (io/file (config :repo)))
     (delete-file-recursively (io/file (config :stats-dir)))
     (.mkdirs (io/file (config :stats-dir)))
     (make-download-count! {})
     (f))))

(defn index-fixture [f]
  (make-index! [])
  (f))

(defn quiet-reporter []
  (reify errors/ErrorReporter
    (-report-error [t e ex id])))

(defrecord Connection []
  component/Lifecycle
  (start [this]
    (if (:connection this)
      this
      (assoc this :connection (jdbc/get-connection (:db test-config)))))
  (stop [this]
    (when (:connection this)
      (.close (:connection this)))
    (assoc this :connection nil)))

(declare ^:dynamic *db*)

(defn with-clean-database [f]
  (let [db-system (component/start (component/system-using
                                    (component/system-map
                                     :connection (->Connection)
                                     :database (sqlite/connector))
                                    {:database {:db :connection}}))]
    (binding [*db* (:database db-system)]
      (try
        (with-out-str
          (migrate/migrate (:connection db-system)))
        (f)
        (finally
          (component/stop db-system))))))

(defn app []
  (web/clojars-app *db* (quiet-reporter)))

(declare ^:dynamic test-port)

(defn run-test-app
  ([f]
   (let [system (component/start (assoc (system/new-system test-config)
                                        :error-reporter (quiet-reporter)))
         server (get-in system [:http :server])
         port (-> server .getConnectors first .getLocalPort)]
     (binding [*db* (:db system)
               test-port port]
       (try
         (with-out-str
           (migrate/migrate (:connection system)))
         (f)
         (finally
           (component/stop system)))))))
