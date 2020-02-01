(ns clojars.tools.combine-cdn-logs
  (:require [clojars.cloudfiles :as cf]
            [clojars.s3 :as s3]
            [clojure.java.io :as io])
  (:import java.io.FileOutputStream)
  (:gen-class))

;; downloads and combines cdn log files from cloudfiles and s3 for the
;; given date, then uploads the combined file to s3 container

(defn connect [username key container-name]
  (cf/connect username key container-name))

(def domap (comp dorun map))

(defn -main [& args]
  (if (not= 9 (count args))
    (println "Usage: raw-log-container cf-user cf-key s3-log-bucket aws-region aws-key aws-secret date output-file")
    (let [[raw-container-name cf-user cf-key
           s3-bucket aws-region aws-key aws-secret
           date output-file] args
          date               (apply format "%s-%s-%s"
                                    (rest (re-find #"(\d{4})(\d{2})(\d{2})" date)))
          dest-file          (io/file output-file)
          name-regex         (re-pattern (str "^" date))
          down-conn          (connect cf-user cf-key raw-container-name)
          s3                 (s3/s3-client aws-key aws-secret aws-region)
          cf-log-files       (eduction
                               (map :name)
                               (filter #(re-find name-regex %))
                               (cf/metadata-seq down-conn))
          s3-log-files       (s3/list-objects s3 s3-bucket date)]
      (with-open [fos (FileOutputStream. dest-file)]
        ;; download and combine cloudfiles logs
        (domap #(with-open [in (cf/artifact-stream down-conn %)]
                  (io/copy in fos))
               cf-log-files)
        ;; then s3 logs
        (domap #(with-open [in (s3/get-object-stream s3 s3-bucket %)]
                  (io/copy in fos))
               s3-log-files)
        
        (when (> (.length dest-file) 0)
          ;; upload combined file
          (with-open [fis (io/input-stream dest-file)]
            (s3/put-object s3 s3-bucket (format "combined-%s.log" date) fis)))))))

