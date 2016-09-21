(ns clojars.tools.upload-repo
  (:require [clojure.java.io :as io]
            [clojars.cloudfiles :as cf]
            [clojars.file-utils :as fu])
  (:gen-class))

(defn upload-file [conn path file existing]
  (if (= (existing path) (fu/checksum file :md5))
    (println "!!> Remote artifact exists, ignoring:" path)
    (do
      (println "==> Uploading:" path)
      (cf/put-file conn path file))))

(defn get-existing [conn subpath]
  (printf "Retrieving current artifact list [subpath: %s] (this may take a while)\n" subpath)
  (into {}
    (map (juxt :name :md5))
    (cf/metadata-seq conn (when subpath {:in-directory subpath}))))

(defn upload-repo [conn repo subpath]
  (let [existing (get-existing conn subpath)
        local-dir (if subpath (io/file repo subpath) (io/file repo))]
    (->> (file-seq local-dir)
      (filter (memfn isFile))
      (run! #(upload-file conn
               (fu/subpath
                 (.getAbsolutePath repo)
                 (.getAbsolutePath %))
               %
               existing)))))

(defn connect [username key container-name]
  (cf/connect username key container-name))

(defn -main [& args]
  (if (< (count args) 4)
    (println "Usage: repo-path container-name user key [subpath]")
    (let [[repo container-name username key subpath] args]
      (upload-repo (connect username key container-name) (io/file repo) subpath))))
