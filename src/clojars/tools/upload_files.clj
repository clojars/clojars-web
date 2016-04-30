(ns clojars.tools.upload-files
  (:require [clojure.java.io :as io]
            [clojars.cloudfiles :as cf])
  (:gen-class))

;; uploads the given files to the top-level of the repo container

(defn connect [username key container-name]
  (cf/connect username key container-name))

(defn -main [& args]
  (if (> 4 (count args))
    (println "Usage: container-name user key file1 [file2 ...]")
    (let [[container-name username key & files] args
          conn (connect username key container-name)]
      (->> files
        (map io/file)
        (run! #(cf/put-file conn (.getName %) %))))))

