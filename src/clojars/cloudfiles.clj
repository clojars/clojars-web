(ns clojars.cloudfiles
  (:require [clojars.file-utils :as fu]
            [clojars.storage :as s]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [org.jclouds.blobstore2 :as jc]
            [clojars.cloudfiles :as cf])
  (:import [org.jclouds.blobstore
            BlobStore
            domain.PageSet
            options.ListContainerOptions]
           com.google.common.hash.HashCode))

(defrecord Connection [bs container])

(defn- apply-map [f & args]
  (apply f (concat (butlast args)
             (mapcat identity (last args)))))

(defn connect
  ([user key container-name]
   (connect user key container-name "rackspace-cloudfiles-us"))
  ([user key container-name provider]
   (assert (some? user))
   (assert (some? key))
   (assert (some? container-name))
   (assert (some? provider))
   (let [bs (jc/blobstore provider user key)]
     (when-not (jc/container-exists? bs container-name)
       (jc/create-container bs container-name :public-read? true))
     (->Connection bs container-name))))

(defn apply-conn [f conn & args]
  (apply f (:bs conn) (:container conn) args))

(defn artifact-exists? [conn path]
  (apply-conn jc/blob-exists? conn path))

(let [metadata-builders
      {:md5 (memfn getETag)
       :content-type #(.getContentType (.getContentMetadata %))
       :created (memfn getCreationDate)
       :last-modified (memfn getLastModified)
       :name (memfn getName)
       :size (memfn getSize)
       :uri (memfn getUri)
       :user-metadata #(into {} (.getUserMetadata %))}]
  (defn metadata->map [md]
    (reduce (fn [m [k f]]
              (assoc m k (f md)))
      {}
      metadata-builders)))

(defn artifact-metadata [conn path]
  (when-let [md (apply-conn jc/blob-metadata conn path)]
    (metadata->map md)))

(let [content-types
      {:asc            :txt
       :bundle         :xml
       :clj            :txt
       :eclipse-plugin :zip
       :gz             "application/gzip"
       :jar            "application/x-java-archive"
       :md5            :txt
       :pom            :xml
       :properties     :txt
       :sha1           :txt
       :txt            "text/plain"
       :xml            "application/xml"
       :zip            "application/zip"
       :unknown        "application/unknown"}]
  (defn content-type [suffix]
    (let [ct (content-types (keyword suffix) :unknown)]
      (if (keyword? ct)
        (content-type ct)
        ct))))

(defn put-file
  "Writes a file to the cloudfiles store.

  If if-changed? is true, only writes the file if it doesn't exist remotely, or its md5 sum doesn't match.

  Returns true if the file was uploaded, nil otherwise."
  ([conn name file]
    (put-file conn name file nil))
  ([conn name file if-changed?]
   (let [file' (io/file file)]
     (when (or (not if-changed?)
               (not= (fu/checksum file :md5)
                     (:md5 (artifact-metadata conn name))))
       (apply-conn jc/put-blob conn
                   (jc/blob name
                            :payload file'
                            :content-type (content-type (last (str/split name #"\.")))
                            :content-md5 (HashCode/fromString (fu/checksum file' :md5))))
       true))))

;; borrowed from jclouds
;; (https://github.com/jclouds/jclouds/blob/master/blobstore/src/main/clojure/org/jclouds/blobstore2.clj)
;; and modified to properly allow recursion in container seq

(defn- container-seq-chunk
  [^BlobStore blobstore container options]
  (apply-map jc/blobs blobstore container
    (if (string? (:after-marker options))
      options
      (dissoc options :after-marker))))

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

(defn metadata-seq
  "Returns a seq of metadata about artifacts.

  Options are:

  * :in-directory path (defaults to nil, giving you the full repo)
  * :max-results n (defaults to nil, giving you all)"
  ([conn]
   (metadata-seq conn nil))
  ([conn options]
   (map metadata->map (apply-conn container-seq conn
                        (merge options {:recursive true :with-details false})))))


(defn remove-artifact
  "Removes the artifact at path.

  If path is a dir, this is a no-op."
  [conn path]
 (apply-conn jc/remove-blob conn path))

;; All deletions require purging from the CDN:
;; https://developer.rackspace.com/docs/cdn/v1/developer-guide/#purge-a-cached-asset
(comment
  (defn delete-version [conn group name version])

  (defn delete-all-versions [conn group name])

  (defn delete-group [bs container group]))

(defrecord CloudfileStorage [conn]
  s/Storage
  (write-artifact [_ path file force-overwrite?]
    (put-file conn path file (not force-overwrite?)))
  (remove-path [_ path]
    (if (.endsWith path "/")
      (run! #(->> % :name (remove-artifact conn)) (metadata-seq conn {:in-directory path}))
      (remove-artifact conn path)))
  (path-exists? [_ path]
    (if (.endsWith path "/")
      (boolean (seq (metadata-seq conn {:in-directory path})))
      (artifact-exists? conn path)))
  (artifact-url [_ path]
    (when-let [uri (->> path (artifact-metadata conn) :uri)]
      (.toURL uri))))

(defn cloudfiles-storage
  ([user token container]
   (cloudfiles-storage (cf/connect user token container)))
  ([cf]
   (->CloudfileStorage cf)))
