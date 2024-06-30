(ns clojars.file-utils
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [digest :as d])
  (:import
   (java.io
    File)))

(set! *warn-on-reflection* true)

(defn checksum-file
  "Returns a file for the sum of `file` of type `type`"
  ^File [file type]
  (let [file' (io/file file)]
    (io/file (.getParentFile file')
             (format "%s.%s" (.getName file') (name type)))))

(def ^:private sum-generators
  {:md5 d/md5
   :sha1 d/sha-1})

(defn checksum
  "Returns the sum of `type` for `file` as a string"
  [file type]
  ((sum-generators type) (io/file file)))

(defn create-checksum-file
  "Creates a sum file of `type` for `file`. Returns the checksum file."
  [file type]
  (let [cf (checksum-file file type)]
    (spit cf (checksum file type))
    cf))

(defn valid-checksum?
  "Checks to see if `sum` of type `type` is valid for `file`"
  [sum file type]
  (= sum (checksum file type)))

(defn valid-checksum-file?
  "Checks to see if a sum file of type `type` exists and is valid for `file`"
  ([file type]
   (valid-checksum-file? file type true))
  ([file type fail-if-missing?]
   (let [sum-file (checksum-file file type)]
     (if (.exists sum-file)
       (valid-checksum? (slurp sum-file) file type)
       (not fail-if-missing?)))))

(defn group->path [group]
  (str/replace group "." "/"))

(defn path->group [path]
  (str/replace path "/" "."))

(defn subpath [prefix path]
  (subs path (inc (count prefix))))
