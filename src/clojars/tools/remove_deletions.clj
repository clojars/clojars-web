(ns clojars.tools.remove-deletions
  "Removes files from cloudfiles that don't exist in the local, on disk repo.
  
  This is used for cleaning up stale maven index files, and for
  artifacts that were deleted during the period where we were
  uploading new deploys to cloudfiles, but the deletion code didn't
  delete from cloudfiles."
  (:require [clojure.java.io :as io]
            [clojars.cloudfiles :as cf])
  (:gen-class))

(defn get-existing [conn subpath]
  (printf "Retrieving current artifact list [subpath: %s] (this may take a while)\n" subpath)
  (map :name (cf/metadata-seq conn (when subpath {:in-directory subpath}))))

(defn remove-deletions [conn repo subpath]
  (run! #(when-not (.exists (io/file repo %))
           (println "!!> artifact doesn't exist locally, removing: " %)
           (cf/remove-artifact conn %))
    (get-existing conn subpath)))

(defn connect [username key container-name]
  (cf/connect username key container-name))

(defn -main [& args]
  (if (< (count args) 4)
    (println "Usage: repo-path container-name user key [subpath]")
    (let [[repo container-name username key subpath] args]
      (remove-deletions (connect username key container-name) (io/file repo) subpath))))
