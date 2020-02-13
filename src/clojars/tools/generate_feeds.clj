(ns clojars.tools.generate-feeds
  (:require [clojars.config :refer [config]]
            [clojars.db :as db]
            [clojars.file-utils :as fu]
            [clojars.maven :as maven]
            [clojars.s3 :as s3]
            [clojure.java.io :as io]
            [clojure.set :as set])
  (:import (java.io FileOutputStream PrintWriter)
           java.util.zip.GZIPOutputStream)
  (:gen-class))

(defn full-feed [db]
  (let [grouped-jars (->> (db/all-jars db)
                          (map (comp #(assoc % :url (:homepage %))
                                     #(select-keys % [:group-id :artifact-id :version
                                                      :description :scm :homepage])
                                     #(set/rename-keys % {:group_name :group-id
                                                          :jar_name   :artifact-id})))
                          (group-by (juxt :group-id :artifact-id))
                          (vals))]
    (for [jars grouped-jars]
      (let [jars (sort-by :version #(maven/compare-versions %2 %1) jars)]
        (-> (first jars)
            (dissoc :version)
            (assoc :versions (vec (distinct (map :version jars))))
            maven/without-nil-values)))))

(defn write-to-file
  ([data file gzip?]
    (write-to-file data file gzip? prn))
  ([data file gzip? out-fn]
   (with-open [w (-> (FileOutputStream. file)
                     (cond-> gzip? (GZIPOutputStream.))
                     (PrintWriter.))]
     (binding [*out* w]
       (doseq [form data]
         (out-fn form))))
   file))

(defn pom-list [s3-bucket]
  (sort
    (into []
          (comp (filter #(.endsWith % ".pom"))
                ;; to match historical list format
                (map (partial str "./")))
          (s3/list-object-keys s3-bucket))))

(defn jar-list [db]
  (->> (db/all-jars db)
    (map (fn [{:keys [group_name jar_name version]}]
           [(if (= group_name jar_name)
              (symbol jar_name)
              (symbol group_name jar_name))
            version]))
    distinct
    sort))

(defn write-sums [f]
  [(fu/create-checksum-file f :md5)
   (fu/create-checksum-file f :sha1)])

(defn put-files [s3-bucket & files]
  (run! #(let [f (io/file %)]
          (s3/put-file s3-bucket (.getName f) f {:ACL "public-read"}))
        files))

(defn generate-feeds [dest db s3-bucket]
  (let [feed-file (str dest "/feed.clj.gz")]
    (apply put-files
           s3-bucket
           (write-to-file (full-feed db) feed-file :gzip)
           (write-sums feed-file)))

  (let [poms (pom-list s3-bucket)
        pom-file (str dest "/all-poms.txt")
        gz-file (str pom-file ".gz")]
    (apply put-files
           s3-bucket
           (write-to-file poms pom-file nil println)
           (write-to-file poms gz-file :gzip println)
           (concat
             (write-sums pom-file)
             (write-sums gz-file))))

  (let [jars (jar-list db)
        jar-file (str dest "/all-jars.clj")
        gz-file (str jar-file ".gz")]
    (apply put-files
           s3-bucket
           (write-to-file jars jar-file nil)
           (write-to-file jars gz-file :gzip)
           (concat
             (write-sums jar-file)
             (write-sums gz-file)))))

(defn -main [& args]
  (if (not= 2 (count args))
    (println "args: aws-key aws-secret")
    (let [[key secret] args
          {:keys [db s3]} (config)]
      (generate-feeds "/tmp"
                      db
                      (s3/s3-client key secret
                                    (:region s3)
                                    (:repo-bucket s3))))))

