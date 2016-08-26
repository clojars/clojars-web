(ns clojars.storage
  (:require [clojars.file-utils :as fu]
            [clojure.java.io :as io])
  (:import org.apache.commons.io.FileUtils))

(defprotocol Storage
  (write-artifact [_ path file force-overwrite?])
  (remove-path [_ path])
  (path-exists? [_ path])
  (artifact-url [_ path]))

;; order matters - the first storage is 'primary', and is the only one
;; used for path-exists? & artifact-url
(defrecord MultiStorage [storages]
  Storage
  (write-artifact [_ path file force-overwrite?]
    (run! #(write-artifact % path file force-overwrite?) storages))
  (remove-path [_ path]
    (run! #(remove-path % path) storages))
  (path-exists? [_ path]
    (path-exists? (first storages) path))
  (artifact-url [_ path]
    (artifact-url (first storages) path)))

(defn multi-storage [& storages]
  (->MultiStorage storages))

(defrecord FileSystemStorage [base-dir]
  Storage
  (write-artifact [_ path file force-overwrite?]
    (let [dest (io/file base-dir path)]
      (when (or force-overwrite?
              (not (.exists dest))
              (not= (fu/checksum dest :md5) (fu/checksum file :md5)))
        (.mkdirs (.getParentFile dest))
        (io/copy file dest))))
  (remove-path [_ path]
    (let [f (io/file base-dir path)]
      (when (.exists f)
        (if (.isDirectory f)
          (FileUtils/deleteDirectory f)
          (.delete f)))))
  (path-exists? [_ path]
    (.exists (io/file base-dir path)))
  (artifact-url [_ path]
    (->> path (io/file base-dir) .toURI .toURL)))

(defn fs-storage [base-dir]
  (->FileSystemStorage base-dir))
