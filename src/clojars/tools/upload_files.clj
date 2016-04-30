(ns clojars.tools.upload-files
  (:require [clojars.cloudfiles :as cf]
            [clojars.file-utils :as fu]
            [clojure.java.io :as io])
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
        (run!
          (fn [f]
            (let [path (.getName f)]
              (if (= (fu/checksum f :md5)
                    (:md5 (cf/artifact-metadata conn path)))
                (println (format "Remote %s exists and has the same md5 checksum, skipping" path))
                (cf/put-file conn path f)))))))))

