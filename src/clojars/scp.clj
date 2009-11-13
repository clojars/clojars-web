(ns clojars.scp
  (:import (java.io InputStream IOException File OutputStream
                    FileOutputStream)
           com.martiansoftware.nailgun.NGContext)
  (:use clojure.contrib.duck-streams)
  (:require [clojars.maven :as maven]
            [clojars.db    :as db])
  (:gen-class
   :methods [#^{:static true}
             [nailMain [com.martiansoftware.nailgun.NGContext] void]]))

(def *max-line-size* 4096)
(def *max-file-size* 10485760)
(def *allowed-suffixes* #{"clj" "xml" "jar"})

(set! *warn-on-reflection* true)

(defn safe-read-line
  ([#^InputStream stream #^StringBuilder builder]
     (when (> (.length builder) *max-line-size*)
       (throw (IOException. "Line too long")))

     (let [c (char (.read stream))]
       (if (= c \newline)
         (str builder)
         (do
           (.append builder c)
           (recur stream builder)))))
  ([stream] (safe-read-line stream (StringBuilder.))))

(defn send-okay [#^NGContext ctx]
  (doto (.out ctx)
    (.print "\0")
    (.flush)))

(defn copy-limit
  "Copies at most n bytes from in to out.  Returns the number of bytes
   copied."
  [#^InputStream in #^OutputStream out n]
  (let [buffer (make-array Byte/TYPE 4096)]
    (loop [bytes 0]
      (if (< bytes n)
        (let [size (.read in buffer 0 (min 4096 (- n bytes)))]
          (if (pos? size)
            (do 
              (.write out buffer 0 size)
              (recur (+ bytes size)))                
            bytes))                
        bytes))))

(defn scp-copy [#^NGContext ctx]
  (let [line #^String (safe-read-line (.in ctx))
        [mode size path] (.split line " " 3)
        size (Integer/parseInt size)
        fn (File. #^String path)
        suffix (last (.split (.getName fn) "\\."))]

    (when (> size *max-file-size*)
      (throw (IOException. (str "Upload too large.  Maximum size is "
                                *max-file-size* " bytes"))))

    (when-not (*allowed-suffixes* suffix)
      (throw (IOException. (str "." suffix 
                                " files are not supported."))))

    (let [f (File/createTempFile "clojars-upload" (str "." suffix))]
      (.deleteOnExit f)
      (send-okay ctx)
      (with-open [fos (FileOutputStream. f)]
        (let [bytes (copy-limit (.in ctx) fos
                                size)]
          (if (>= bytes size)
            {:name (.getName fn), :file f, :size size, :suffix suffix,
             :mode mode}
            (throw (IOException. (str "Upload truncated.  Expected "
                                      size " bytes but got " bytes)))))))))

(defmacro printerr [& strs]
  `(.println (.err ~'ctx) (str ~@(interleave strs (repeat " ")))))

(defmulti read-metadata :suffix)
(defmethod read-metadata "xml" [f] 
  (let [model (maven/read-pom (:file f))
        jarmap (maven/model-to-map model)]
    [[model jarmap]]))

;; TODO: probably want to show an error if the file contains no defjars
(defmethod read-metadata "clj" [f]
  (for [jarmap (maven/read-jarspec (:file f))]
    (let [model (maven/make-model jarmap)]
      [model jarmap])))

(defn jar-names
  "Construct a few possible name variations a jar might have."
  [jarmap]
  [ (str (name (:name jarmap)) "-" (:version jarmap) ".jar")
    (str (name (:name jarmap)) ".jar")])

(defn finish-deploy [#^NGContext ctx, files]
  (let [account (first (.getArgs ctx))
        act-group (str "org.clojars." account)
        metadata (filter #(#{"xml" "clj"} (:suffix %)) files)
        jars     (filter #(#{"jar"}       (:suffix %)) files)
        jarfiles (into {} (map (juxt :name :file) jars))]

    (doseq [metafile metadata
            [model jarmap] (read-metadata metafile)
            :let [names (jar-names jarmap)
                  jarmap (if (:group jarmap) jarmap 
                             (assoc jarmap :group act-group))]]

      (when (not= (:group jarmap) act-group)
        (throw (Exception.
                (str "Custom group ids are not supported yet. "
                     " For now please use "
                     act-group " instead of " (:group jarmap)))))

      (if-let [jarfile (some jarfiles names)]
        (do          
          (printerr "\nDeploying" (:group jarmap) (:name jarmap) (:version jarmap))          
          (maven/deploy-model jarfile model
                              "file:///home/clojars/repo")
          (db/add-jar (first (.getArgs ctx)) jarmap))
        (throw (Exception. (str "You need to give me one of: " names)))))
    (printerr (str "\nSuccess! Your jars are now available from "
                   "http://clojars.org/" account "/" name))
    (.flush (.err ctx))))

(defn nail [#^NGContext ctx]
  (let [old-out System/out]
   (try
    (System/setOut (.err ctx))
    (let [in (.in ctx)
          err (.err ctx)
          out (.out ctx)
          account (first (.getArgs ctx))]
    
      (when-not account
        (throw (Exception. "I don't know who you are!")))

      (doto (.err ctx)
        (.println (str "Welcome to Clojars, " account "!"))
        (.flush))
     
      (loop [files [], okay true]
        (when (> (count files) 100)
          (throw (IOException. "Too many files uploaded at once")))

        (when okay
          (send-okay ctx))

        (let [cmd (char (.read in))]
          (condp = cmd
            (char 0)      (do (recur files false))
            \C            (recur (conj files (scp-copy ctx)) true)
            (char 65535)  (finish-deploy ctx files)
            ;; TODO: will need other commands for maven support
            (throw (IOException. (str "Unknown scp command: '" (int cmd) "'")))))))

    (catch Throwable t
      (.printStackTrace t))
    (finally (System/setOut old-out)))))

(defn -nailMain [context]
  (nail context))

;(sorted-set-by compare 1 "a")
