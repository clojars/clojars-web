(ns clojars.tools.combine-cdn-logs
  (:require [clojars.s3 :as s3]
            [clojure.java.io :as io])
  (:import java.io.FileOutputStream)
  (:gen-class))

;; downloads and combines cdn log files from s3 for the
;; given date, then uploads the combined file to s3 bucket

(def domap (comp dorun map))

(defn -main [& args]
  (if (not= 6 (count args))
    (println "Usage: s3-log-bucket aws-region aws-key aws-secret date output-file")
    (let [[s3-bucket aws-region aws-key aws-secret
           date output-file] args
          date               (apply format "%s-%s-%s"
                                    (rest (re-find #"(\d{4})(\d{2})(\d{2})" date)))
          dest-file          (io/file output-file)
          s3                 (s3/s3-client aws-key aws-secret aws-region s3-bucket)
          s3-log-files       (s3/list-object-keys s3 date)]
      (with-open [fos (FileOutputStream. dest-file)]
        ;; download and combine s3 logs
        (domap #(with-open [in (s3/get-object-stream s3 %)]
                  (io/copy in fos))
               s3-log-files))
        
      (when (> (.length dest-file) 0)
        ;; upload combined file
        (s3/put-file s3 (format "combined-%s.log" date) dest-file)))))

