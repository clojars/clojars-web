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

(defn create-s3-bucket [bucket]
  (s3/s3-client bucket))

(defn -main [& args]
  (if (< (count args) 2)
    (println "Usage: repo-path bucket-name [subpath]")
    (let [[repo bucket subpath] args]
      (prn [repo subpath])
      (remove-deletions (create-s3-bucket bucket)
                        (io/file repo) subpath))))
