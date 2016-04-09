(ns clojars.cloudfiles
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [org.jclouds.blobstore2 :as jc]))

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

;; All deletions require purging from the CDN:
;; https://developer.rackspace.com/docs/cdn/v1/developer-guide/#purge-a-cached-asset
(comment
  (defn delete-version [conn group name version])

  (defn delete-all-versions [conn group name])

  (defn delete-group [bs container group]))


