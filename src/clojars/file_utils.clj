(ns clojars.file-utils
  (:require [clojure.java.io :as io]
            [digest :as d]))

(defn sum-file
  "Returns a file for the sum of `file` of type `type`"
  [file type]
  (let [file' (io/file file)]
    (io/file (.getParentFile file')
             (format "%s.%s" (.getName file') (name type)))))

(defn- create-sum [f file type]
  (let [file' (io/file file)]
    (spit (sum-file file' type) (f file'))))

(def ^:private sum-generators
  {:md5 d/md5
   :sha1 d/sha-1})

(defn create-md5-sum
  "Creates md5 sum file for `file`"
  [file]
  (create-sum (sum-generators :md5) file :md5))

(defn create-sha1-sum
  "Creates sha1 sum file for `file`"
  [file]
  (create-sum (sum-generators :sha1) file :sha1))

(defn create-sums
  "Creates md5 and sha1 sum files for `file`"
  [file]
  (create-md5-sum file)
  (create-sha1-sum file))

(defn valid-sum?
  "Checks to see if a sum of type `type` exists and is valid for `file`"
  [file type]
  (let [sig-file (sum-file file type)]
    (and (.exists sig-file)
         (= ((sum-generators type) (io/file file))
            (slurp sig-file)))))

(defn valid-sums?
  "Checks to see if both md5 and sha1 sums exist and are valid for `file`"
  [file]
  (reduce (fn [valid? sig-type]
            (and valid?
                 (valid-sum? file sig-type)))
          true
          [:md5 :sha1]))