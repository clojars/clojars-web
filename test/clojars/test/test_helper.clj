(ns clojars.test.test-helper
  (import java.io.File)
  (:require [clojars.db :as db]
            [clojars.config :as config]
            [korma.db :as sql]
            [clojure.test :as test]
            [clojure.java.io :as io]))

(defn delete-file-recursively
  "Delete file f. If it's a directory, recursively delete all its contents.
Raise an exception if any deletion fails unless silently is true."
  [f]
  (let [f (io/file f)]
    (when (.isDirectory f)
      (doseq [child (.listFiles f)]
        (delete-file-recursively child)))
    (io/delete-file f)))

(defn use-fixtures []
  (test/use-fixtures :each
                     (fn [f]
                       (let [file (File. (:repo config/config))]
                         (when (.exists file)
                           (delete-file-recursively file)))
                       (sql/transaction
                        (sql/rollback)
                        (f)))))