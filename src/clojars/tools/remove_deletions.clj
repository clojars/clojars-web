(ns clojars.tools.remove-deletions
  "Removes files from cloudfiles that don't exist in the local, on disk repo.
  This is for cleaning up artifacts that were deleted during the
  period where we were uploading new deploys to cloudfiles, but the
  deletion code didn't delete from cloudfiles."
  (:require [clojure.java.io :as io]
            [clojars.cloudfiles :as cf])
  (:gen-class))

(defn get-existing [conn]
  (println "Retrieving current artifact list (this will take a while)")
  (map :name (cf/metadata-seq conn)))

(defn remove-deletions [conn repo]
  (run! #(when-not (.exists (io/file repo %))
           (println "!!> artifact doesn't exist locally, removing: " %)
           (cf/remove-artifact conn %))
    (get-existing conn)))

(defn connect [username key container-name]
  (cf/connect username key container-name))

(defn -main [& args]
  (if (not= 4 (count args))
    (println "Usage: repo-path container-name user key")
    (let [[repo container-name username key] args]
      (remove-deletions (connect username key container-name) (io/file repo)))))
