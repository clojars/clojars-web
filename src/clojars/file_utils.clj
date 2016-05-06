(ns clojars.file-utils
  (:require [clojure.java.io :as io]
            [digest :as d]))

(defn checksum-file
  "Returns a file for the sum of `file` of type `type`"
  [file type]
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
   (let [sig-file (checksum-file file type)]
     (if (.exists sig-file)
       (valid-checksum? (slurp sig-file) file type)
       (not fail-if-missing?)))))

