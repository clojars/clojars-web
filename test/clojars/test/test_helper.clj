(ns clojars.test.test-helper
  (import java.io.File)
  (:require [clojars.db :as db]
            [clojars.config :as config]
            [korma.db :as kdb]
            [clojure.test :as test]
            [clojure.java.io :as io]
            [clojure.java.jdbc :as jdbc]))

(defn delete-file-recursively
  "Delete file f. If it's a directory, recursively delete all its contents."
  [f]
  (let [f (io/file f)]
    (when (.exists f)
      (when (.isDirectory f)
        (doseq [child (.listFiles f)]
          (delete-file-recursively child)))
      (io/delete-file f))))

(defn use-fixtures []
  (test/use-fixtures :each
                     (fn [f]
                       (let [file (File. (:repo config/config))]
                         (delete-file-recursively file))
                       (f)
                       (jdbc/with-connection (kdb/get-connection @kdb/_default)
                         (jdbc/do-commands
                          "delete from users;"
                          "delete from jars;"
                          "delete from deps;"
                          "delete from groups;")))))
