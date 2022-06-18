(ns clojars.storage
  (:require
   [clojars.cdn :as cdn]
   [clojars.errors :as errors]
   [clojars.file-utils :as fu]
   [clojars.s3 :as s3]
   [clojure.java.io :as io])
  (:import org.apache.commons.io.FileUtils))

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

(defrecord FileSystemStorage [base-dir]
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
        (filter (memfn isFile))
        (map #(fu/subpath (.getAbsolutePath base-dir) (.getAbsolutePath %))))))
  (artifact-url [_ path]
    (->> path (io/file base-dir) .toURI .toURL)))

(defn fs-storage [base-dir]
  (->FileSystemStorage (io/file base-dir)))

(defrecord S3Storage [client]
  Storage
  (-write-artifact [_ path file _force-overwrite?]
    (s3/put-file client path file {:ACL "public-read"}))
  (remove-path [_ path]
    (if (.endsWith path "/")
      (run! (partial s3/delete-object client)
            (s3/list-object-keys client path))
      (s3/delete-object client path)))
  (path-exists? [_ path]
    (if (.endsWith path "/")
      (boolean (seq (s3/list-objects client path)))
      (s3/object-exists? client path)))
  (path-seq [_ path]
    (s3/list-object-keys client path))
  (artifact-url [_ _path]
    (throw (ex-info "Not implemented" {}))))

(defn s3-storage
  ([key token region bucket-name]
   (s3-storage (s3/s3-client key token region bucket-name)))
  ([client]
   (->S3Storage client)))

(defn- purge
  [cdn-token cdn-url path]
  (when (and cdn-token
             cdn-url
             (not= "NOTSET" cdn-token))
    (let [{:keys [status] :as resp} (cdn/purge cdn-token cdn-url path)]
      (when (not= "ok" status)
        (throw (ex-info (format "Fastly purge failed for %s" path) resp))))))

(defn- retry 
  [f {:keys [n sleep jitter error-reporter]
                 :or {sleep 1000
                      jitter 20
                      n 3}}] 
  (loop [i n] 
    (if (> i 0)
      (let [result (try
                     (f)
                     (catch Exception t
                       t))]
        (if (instance? Exception result)
          (do (errors/report-error error-reporter result)
              (Thread/sleep (+ sleep (rand-int jitter)))
              (recur (dec i)))
          result))
      (throw (ex-info (format "Retry limit %s has been reached" n) {:n n :sleep sleep :jitter jitter})))))

(defrecord CDNStorage [error-reporter cdn-token cdn-url]
  Storage
  (-write-artifact [_ path _ _]
    ;; Purge any file in the deploy in case it has been requested in
    ;; the last 24 hours, since fastly will cache the 404.  Run in a
    ;; future so we don't have to wait for the request to finish to
    ;; complete the deploy.
    (future (retry
              #(purge cdn-token cdn-url path)
              {:error-reporter error-reporter})))
  (remove-path [_ path]
    (purge cdn-token cdn-url path))
  (path-exists? [_ _])
  (artifact-url [_ _]))

(defn cdn-storage [error-reporter cdn-token cdn-url]
  (->CDNStorage error-reporter cdn-token cdn-url))

(defn full-storage [error-reporter on-disk-repo repo-bucket cdn-token cdn-url]
  (multi-storage
    (fs-storage on-disk-repo)
    (s3-storage repo-bucket)
    (cdn-storage error-reporter cdn-token cdn-url)))
