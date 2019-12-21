(ns clojars.storage
  (:require [clojars.cdn :as cdn]
            [clojars.cloudfiles :as cf]
            [clojars.file-utils :as fu]
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

(defrecord CloudfileStorage [conn]
  Storage
  (-write-artifact [_ path file force-overwrite?]
    (cf/put-file conn path file (not force-overwrite?)))
  (remove-path [_ path]
    (if (.endsWith path "/")
      (run! #(->> % :name (cf/remove-artifact conn))
        (cf/metadata-seq conn {:in-directory path}))
      (cf/remove-artifact conn path)))
  (path-exists? [_ path]
    (if (.endsWith path "/")
      (boolean (seq (cf/metadata-seq conn {:in-directory path})))
      (cf/artifact-exists? conn path)))
  (path-seq [_ path]
    (map :name (cf/metadata-seq conn {:in-directory path})))
  (artifact-url [_ path]
    (when-let [uri (->> path (cf/artifact-metadata conn) :uri)]
      (.toURL uri))))

(defn cloudfiles-storage
  ([user token container]
   (cloudfiles-storage (cf/connect user token container)))
  ([cf]
   (->CloudfileStorage cf)))

;; we only care about removals for purging
(defrecord CDNStorage [cdn-token cdn-url]
  Storage
  (-write-artifact [_ _ _ _])
  (remove-path [t path]
    (if (and cdn-token cdn-url)
      (let [{:keys [status] :as resp} (cdn/purge cdn-token cdn-url path)]
        (when (not= "ok" status)
          ;; this should only be triggered from the admin ns in the
          ;; repl, so a println is fine. If we ever implement removals
          ;; from the UI, this will need to report elsewhere
          (println (format "CDN purge failed for %s" path))
          (prn resp)))
      (println "Either no CDN key or host specified, purging skipped:" t)))
  (path-exists? [_ _])
  (artifact-url [_ _]))

(defn cdn-storage [cdn-token cdn-url]
  (->CDNStorage cdn-token cdn-url))

(defn full-storage [on-disk-repo cloudfiles cdn-token cdn-url]
  (multi-storage
    (fs-storage on-disk-repo)
    (cloudfiles-storage cloudfiles)
    (cdn-storage cdn-token cdn-url)))
