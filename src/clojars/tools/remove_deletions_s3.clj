(ns clojars.tools.remove-deletions-s3
  "Removes files from s3 that don't exist in the local, on disk repo.
  
  This is used for cleaning up stale maven index files."
  (:gen-class)
  (:require
   [clojars.s3 :as s3]
   [clojure.java.io :as io]))

(defn get-existing [s3-bucket subpath]
  (printf "Retrieving current artifact list [subpath: %s] (this may take a while)\n" subpath)
  (s3/list-object-keys s3-bucket subpath))

(defn remove-deletions [s3-bucket repo subpath]
  (run! #(when-not (.exists (io/file repo %))
           (println "!!> artifact doesn't exist locally, removing: " %)
           (s3/delete-object s3-bucket %))
        (get-existing s3-bucket subpath)))

(defn create-s3-bucket [access-key-id secret-access-key region bucket]
  (s3/s3-client access-key-id secret-access-key region bucket))

(defn -main [& args]
  (if (< (count args) 5)
    (println "Usage: repo-path bucket-name region key secret [subpath]")
    (let [[repo bucket region key secret subpath] args]
      (prn [repo subpath])
      (remove-deletions (create-s3-bucket key secret region bucket)
                        (io/file repo) subpath))))
