(ns clojars.tools.generate-feeds
  (:gen-class)
  (:require
   [clojars.config :refer [config]]
   [clojars.db :as db]
   [clojars.file-utils :as fu]
   [clojars.maven :as maven]
   [clojars.s3 :as s3]
   [clojars.util :as util]
   [clojure.java.io :as io]
   [clojure.set :as set])
  (:import
   (java.io
    FileOutputStream
    OutputStream
    PrintWriter)
   java.util.zip.GZIPOutputStream))

(set! *warn-on-reflection* true)

(defn- versions-data
  [jars]
  (let [distinct-jars (into [] (util/distinct-by :version) jars)]
    {:versions (mapv :version distinct-jars)
     :versions-meta (mapv
                     (fn [{:keys [created scm version]}]
                       (util/assoc-some {:version version
                                         :release-date created}
                                        :scm-tag (:tag scm)))
                     distinct-jars)}))

(defn full-feed [db]
  (let [grouped-jars (->> (db/all-jars db)
                          (maven/sort-by-version)
                          (reverse) ;; We want the most recent version first
                          (group-by (juxt :group_name :jar_name)))]
    (->> (for [[[group-id artifact-id] jars] grouped-jars]
           (try
             (let [base-jar (first jars)]
               (-> base-jar
                   (select-keys [:group_name :jar_name
                                 :description :scm :homepage])
                   (set/rename-keys {:group_name :group-id
                                     :jar_name   :artifact-id})
                   (assoc :url (:homepage base-jar))
                   (merge (versions-data jars))
                   maven/without-nil-values))
             (catch Exception e
               (printf "Got exception when processing %s:%s, skipping: %s\n"
                       group-id artifact-id (.getMessage e)))))
         (keep identity))))

(defn write-to-file
  ([data filename gzip?]
   (write-to-file data filename gzip? prn))
  ([data ^String filename gzip? out-fn]
   (with-open [w (-> (FileOutputStream. filename)
                     (cond-> gzip? (GZIPOutputStream.))
                     (as-> ^OutputStream % (PrintWriter. %)))]
     (binding [*out* w]
       (doseq [form data]
         (out-fn form))))
   filename))

(defn pom-list [s3-bucket]
  (sort
   (into []
         (comp (filter #(.endsWith ^String % ".pom"))
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

(defn -main [feed-dir env]
  (let [{:keys [db s3]} (config (keyword env))]
    (generate-feeds feed-dir
                    db
                    (s3/s3-client (:repo-bucket s3)))))
