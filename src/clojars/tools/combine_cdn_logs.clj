(ns clojars.tools.combine-cdn-logs
  (:require [clojars.cloudfiles :as cf]
            [clojure.java.io :as io])
  (:import java.io.FileOutputStream)
  (:gen-class))

;; downloads and combines cdn log files for the given date, then
;; uploads the combined file to another container, then removes the
;; source logs from the first container

(defn connect [username key container-name]
  (cf/connect username key container-name))

(def domap (comp dorun map))

(defn -main [& args]
  (if (not= 6 (count args))
    (println "Usage: raw-log-container combined-log-container user key date output-file")
    (let [[raw-container-name combined-container-name username key date output-file] args
          dest-file  (io/file output-file)
          name-regex (re-pattern (str "^" date))
          down-conn  (connect username key raw-container-name)
          up-conn    (connect username key combined-container-name)
          log-files  (eduction
                       (map :name)
                       (filter #(re-find name-regex %))
                       (cf/metadata-seq down-conn))]
      (with-open [fos (FileOutputStream. dest-file)]
        ;; download and combine
        (domap #(with-open [in (cf/artifact-stream down-conn %)]
                  (io/copy in fos))
          log-files)
        (when (> (.length dest-file) 0)
          ;; upload combined file
          (cf/put-file up-conn (format "combined-%s.log" date) dest-file :if-changed))
        ;; delete raw files
        (domap (partial cf/remove-artifact down-conn) log-files)))))

