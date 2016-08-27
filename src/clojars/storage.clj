(ns clojars.storage
  (:require [clojars.file-utils :as fu]
            [clojars.queue :as q]
            [clojure.java.io :as io]
            [clojars.cloudfiles :as cf])
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

;; write-artifact/remove-path are async
(defrecord AsyncStorage [storage name queueing]
  Storage
  (write-artifact [_ path file force-overwrite?]
    (q/enqueue! queueing name {:fn 'clojars.storage/write-artifact
                               :args [path file force-overwrite?]}))
  (remove-path [_ path]
    (q/enqueue! queueing name {:fn 'clojars.storage/remove-path
                               :args [path]}))
  (path-exists? [_ path]
    (path-exists? storage path))
  (artifact-url [_ path]
    (artifact-url storage path)))

(defn async-handler [storage {:keys [fn args]}]
  (apply (resolve fn) storage args))

(defn async-storage [name queueing storage]
  (q/register-handler queueing name (partial async-handler storage))
  (->AsyncStorage storage name queueing))

(defrecord CloudfileStorage [conn]
  Storage
  (write-artifact [_ path file force-overwrite?]
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
  (artifact-url [_ path]
    (when-let [uri (->> path (cf/artifact-metadata conn) :uri)]
      (.toURL uri))))

(defn cloudfiles-storage
  ([user token container]
   (cloudfiles-storage (cf/connect user token container)))
  ([cf]
   (->CloudfileStorage cf)))

(defn full-storage [on-disk-repo cloudfiles queue]
  (multi-storage
    (fs-storage on-disk-repo)
    (async-storage :cloudfiles queue
      (cloudfiles-storage cloudfiles))
    ;; TODO: cdn storage
    ))
