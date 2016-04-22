(ns clojars.tools.upload-repo
  (:require [clojure.java.io :as io]
            [clojars.cloudfiles :as cf])
  (:gen-class))

(defn upload-file [conn path file existing]
  (if (existing path)
    (println "!!> Remote artifact exists, ignoring:" path)
    (do
      (println "==> Uploading:" path)
      (cf/put-file conn path file))))

(defn get-existing [conn]
  (println "Retrieving current artifact list (this will take a while)")
  (into #{} (cf/artifact-seq conn)))

(defn upload-repo [conn repo]
  (let [existing (get-existing conn)]
    (->> (file-seq repo)
      (filter (memfn isFile))
      (run! #(upload-file conn
               (cf/remote-path
                 (.getAbsolutePath repo)
                 (.getAbsolutePath %))
               %
               existing)))))

(defn -main [& args]
  (if (not= 4 (count args))
    (println "Usage: repo-path container-name user key")
    (let [[repo container-name username key] args]
      (upload-repo (cf/connect username key container-name) (io/file repo)))))
