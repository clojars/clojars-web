(ns clojars.test.test-helper
  (:require [clojars
             [config :refer [config]]
             [db :as db]
             [errors :as errors]
             [search :as search]
             [stats :as stats]
             [system :as system]
             [web :as web]]
            [clojars.db.migrate :as migrate]
            [clojure.java
             [io :as io]
             [jdbc :as jdbc]]
            [clucy.core :as clucy]
            [com.stuartsierra.component :as component])
  (:import java.io.File))

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

(defn default-fixture [f]
  (using-test-config
   (fn []
     (delete-file-recursively (io/file (config :repo)))
     (f))))

(defn quiet-reporter []
  (reify errors/ErrorReporter
    (-report-error [t e ex id])))

(declare ^:dynamic *db*)

(defn with-clean-database [f]
  (binding [*db* {:connection (jdbc/get-connection (:db test-config))}]
    (try
      (with-out-str
        (migrate/migrate *db*))
      (f)
      (finally (.close (:connection *db*))))))

(defn no-stats []
  (stats/->MapStats {}))

(defn no-search []
  (reify search/Search))

(declare ^:dynamic test-port)

(defn app []
  (web/clojars-app *db* (quiet-reporter) (no-stats) (no-search)))

(declare ^:dynamic system)

(defn app-from-system []
  ;; TODO once the database is a protocol, review
  ;; usage of this to move things into unit tests
  (web/handler-optioned system))

(defn run-test-app
  ([f]
   (binding [system (component/start (assoc (system/new-system test-config)
                                            :error-reporter (quiet-reporter)
                                            :index-factory #(clucy/memory-index)
                                            :stats (no-stats)))]
     (let [server (get-in system [:http :server])
           port (-> server .getConnectors first .getLocalPort)]
       (binding [test-port port]
         (try
           (with-out-str
             (migrate/migrate (get-in system [:db :spec])))
           (f)
           (finally
             (component/stop system))))))))
