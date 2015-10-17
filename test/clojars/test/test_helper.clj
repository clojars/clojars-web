(ns clojars.test.test-helper
  (:import java.io.File)
  (:require [clojars.db :as db]
            [clojars.db.migrate :as migrate]
            [clojars.config :refer [config]]
            [clojars.web :as web]
            [clojars.main :as main]
            [clojars.dev.setup :as setup]
            [clucy.core :as clucy]
            [clojure.java.shell :as sh]
            [clojure.java.jdbc :as jdbc]
            [clojure.java.io :as io]
            [clojars.search :as search]))

(def local-repo (io/file (System/getProperty "java.io.tmpdir")
                         "clojars" "test" "local-repo"))
(def local-repo2 (io/file (System/getProperty "java.io.tmpdir")
                         "clojars" "test" "local-repo2"))

(def test-config {:bind "127.0.0.1"
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

(declare ^:dynamic *db*)

(defn with-clean-database [f]
  (binding [*db* {:connection (jdbc/get-connection (:db test-config))}]
    (try
      (with-out-str
        (migrate/migrate *db*))
      (setup/reset-db! *db*)
      (f)
      (finally (.close (:connection *db*))))))

(declare test-port)

(defn run-test-app
  ([f]
   (let [server (main/start-jetty *db* 0)
         port (-> server .getConnectors first .getLocalPort)]
     (with-redefs [test-port port]
       (try
         (f)
         (finally
           (.stop server)))))))
