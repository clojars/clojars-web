(ns clojars.cloudfiles
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [org.jclouds.blobstore2 :as jc])
  (:import [org.jclouds.blobstore
            BlobStore
            domain.PageSet
            options.ListContainerOptions]))

(defrecord Connection [bs container])

(defn connect
  ([user key container-name]
   (connect user key container-name "rackspace-cloudfiles-us"))
  ([user key container-name provider]
   (let [bs (jc/blobstore provider user key)]
     (when-not (jc/container-exists? bs container-name)
       (jc/create-container bs container-name :public-read? true))
     (->Connection bs container-name))))

(defn- apply-conn [f conn & args]
  (apply f (:bs conn) (:container conn) args))

(defn remote-path [prefix path]
  (subs path (inc (count prefix))))

(defn artifact-exists? [conn path]
  (apply-conn jc/blob-exists? conn path))

(defn put-file [conn name file]
  (apply-conn jc/put-blob conn (jc/blob name :payload file)))

;; borrowed from jclouds
;; (https://github.com/jclouds/jclouds/blob/master/blobstore/src/main/clojure/org/jclouds/blobstore2.clj)
;; and modified to properly allow recursion in container seq

(defn- container-seq-chunk
  [^BlobStore blobstore container options]
  (apply jc/blobs blobstore container
    (mapcat identity
      (if (string? (:after-marker options))
        options
        (dissoc options :after-marker)))))

(defn- container-seq-chunks [^BlobStore blobstore container options]
  (when (:after-marker options) ;; When getNextMarker returns null, there's no more.
    (let [chunk (container-seq-chunk blobstore container options)]
      (lazy-seq (cons chunk
                  (container-seq-chunks blobstore container
                    (assoc options
                      :after-marker (.getNextMarker ^PageSet chunk))))))))

(defn container-seq
  "Returns a lazy seq of all blobs in the given container."
  ([^BlobStore blobstore container]
     (container-seq blobstore container nil))
  ([^BlobStore blobstore container options]
     ;; :start has no special meaning, it is just a non-null (null indicates
     ;; end), non-string (markers are strings).
   (#'jc/concat-elements (container-seq-chunks blobstore container
                           (assoc options :after-marker :start)))))

;; end jclouds code

(defn artifact-seq [conn]
  (map (memfn getName) (apply-conn container-seq conn {:recursive true :with-details false})))


;; All deletions require purging from the CDN:
;; https://developer.rackspace.com/docs/cdn/v1/developer-guide/#purge-a-cached-asset
(comment
  (defn delete-version [conn group name version])

  (defn delete-all-versions [conn group name])

  (defn delete-group [bs container group]))


