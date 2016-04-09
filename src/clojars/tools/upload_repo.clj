(ns clojars.tools.upload-repo
  (:require [clojure.java.io :as io]
            [clojars.cloud-files :as cf])
  (:gen-class))

(defn upload-file [conn path file]
  (if (cf/artifact-exists? conn path)
    (println "==> Remote artifact exists, ignoring:" path)
    (cf/put-file conn path file)))

(defn upload-repo [conn repo]
  (->> (file-seq repo)
    (filter (memfn isFile))
    (run! #(upload-file conn
             (cf/remote-path
               (.getAbsolutePath repo)
               (.getAbsolutePath %))
             %))))

(defn -main [& args]
  (if (not= 4 (count args))
    (println "Usage: repo-path container-name user key")
    (let [[repo container-name username key] args]
      (upload-repo (cf/connect username key container-name) (io/file repo)))))
