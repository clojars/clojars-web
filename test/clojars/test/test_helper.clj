(ns clojars.test.test-helper
  (import java.io.File)
  (:require [clojars.db :as db]
            [clojars.db.migrate :as migrate]
            [clojars.config :refer [config]]
            [korma.db :as kdb]
            [clucy.core :as clucy]
            [clojure.test :as test]
            [clojure.java.shell :as sh]
            [clojure.java.io :as io]
            [clojure.java.jdbc :as jdbc]))

(def local-repo (io/file (System/getProperty "java.io.tmpdir")
                         "clojars" "test" "local-repo"))
(def local-repo2 (io/file (System/getProperty "java.io.tmpdir")
                         "clojars" "test" "local-repo2"))

(defn delete-file-recursively
  "Delete file f. If it's a directory, recursively delete all its contents."
  [f]
  (let [f (io/file f)]
    (when (.exists f)
      (when (.isDirectory f)
        (doseq [child (.listFiles f)]
          (delete-file-recursively child)))
      (io/delete-file f))))

(defonce migrate
  (delay
   (let [db (:subname (:db config))]
     (when-not (.exists (io/file db))
       (.mkdirs (.getParentFile (io/file db)))
       (sh/sh "sqlite3" db :in (slurp "clojars.sql"))))
   (migrate/-main)))

(defn default-fixture [f]
  (force migrate)
  (delete-file-recursively (io/file (config :repo)))
  (delete-file-recursively (io/file (config :event-dir)))
  (.mkdirs (io/file (config :event-dir)))
  (delete-file-recursively (io/file (config :stats-dir)))
  (.mkdirs (io/file (config :stats-dir)))
  (spit (io/file (config :stats-dir) "all.edn") "{}")
  (jdbc/with-connection (kdb/get-connection @kdb/_default)
    (jdbc/do-commands
     "delete from users;" "delete from jars;" "delete from groups;"))
  (f))

(defn index-fixture [f]
  (delete-file-recursively (io/file (config :index-path)))
  (with-open [index (clucy/disk-index (config :index-path))]
    (clucy/add index {:dummy true})
    (clucy/search-and-delete index "dummy:true"))
  (f))