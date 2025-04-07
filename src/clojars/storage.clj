(ns clojars.storage
  (:require
   [clojars.cdn :as cdn]
   [clojars.file-utils :as fu]
   [clojars.retry :as retry]
   [clojars.s3 :as s3]
   [clojure.java.io :as io])
  (:import
   java.io.File
   org.apache.commons.io.FileUtils))

(set! *warn-on-reflection* true)

(defprotocol Storage
  (-write-artifact [_ path file force-overwrite?])
  (remove-path [_ path])
  (path-exists? [_ path])
  (path-seq [_ path])
  (artifact-url [_ path]))

(defn write-artifact
  ([storage path file]
   (write-artifact storage path file false))
  ([storage path file force-overwrite?]
   (-write-artifact storage path file force-overwrite?)))

;; order matters - the first storage is 'primary', and is the only one
;; used for path-exists? & artifact-url
(defrecord MultiStorage [storages]
  Storage
  (-write-artifact [_ path file force-overwrite?]
    (run! #(-write-artifact % path file force-overwrite?) storages))
  (remove-path [_ path]
    (run! #(remove-path % path) storages))
  (path-exists? [_ path]
    (path-exists? (first storages) path))
  (path-seq [_ path]
    (path-seq (first storages) path))
  (artifact-url [_ path]
    (artifact-url (first storages) path)))

(defn multi-storage [& storages]
  (->MultiStorage storages))

(defrecord FileSystemStorage [^File base-dir]
  Storage
  (-write-artifact [_ path file force-overwrite?]
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
  (path-seq [t path]
    (when (path-exists? t path)
      (->> (io/file base-dir path)
           file-seq
           (filter (fn [^File f] (.isFile f)))
           (map #(fu/subpath (.getAbsolutePath base-dir) (.getAbsolutePath ^File %))))))
  (artifact-url [_ path]
    (->> path (io/file base-dir) .toURI .toURL)))

(defn fs-storage [base-dir]
  (->FileSystemStorage (io/file base-dir)))

(defrecord S3Storage [client]
  Storage
  (-write-artifact [_ path file _force-overwrite?]
    (s3/put-file client path file {:ACL "public-read"}))
  (remove-path [_ path]
    (if (.endsWith ^String path "/")
      (run! (partial s3/delete-object client)
            (s3/list-object-keys client path))
      (s3/delete-object client path)))
  (path-exists? [_ path]
    (if (.endsWith ^String path "/")
      (boolean (seq (s3/list-objects client path)))
      (s3/object-exists? client path)))
  (path-seq [_ path]
    (s3/list-object-keys client path))
  (artifact-url [_ _path]
    (throw (ex-info "Not implemented" {}))))

(defn s3-storage
  [client]
  (->S3Storage client))

(defn- purge
  [cdn-token cdn-url path]
  (when (and cdn-token cdn-url)
    (let [{:keys [status] :as resp} (cdn/purge cdn-token cdn-url path)]
      (when (not= "ok" status)
        (throw (ex-info (format "Fastly purge failed for %s" path) resp))))))

(defrecord CDNStorage [error-reporter cdn-token cdn-url]
  Storage
  (-write-artifact [_ path _ _]
    ;; Purge any file in the deploy in case it has been requested in
    ;; the last 24 hours, since fastly will cache the 404.  Run in a
    ;; future so we don't have to wait for the request to finish to
    ;; complete the deploy.
    (future (retry/retry
             {:error-reporter error-reporter}
             (purge cdn-token cdn-url path))))
  (remove-path [_ path]
    (purge cdn-token cdn-url path))
  (path-exists? [_ _])
  (path-seq [_ _])
  (artifact-url [_ _]))

(defn cdn-storage [error-reporter cdn-token cdn-url]
  (->CDNStorage error-reporter cdn-token cdn-url))

(defn full-storage [error-reporter on-disk-repo repo-bucket cdn-token cdn-url]
  (multi-storage
   (fs-storage on-disk-repo)
   (s3-storage repo-bucket)
   (cdn-storage error-reporter cdn-token cdn-url)))
