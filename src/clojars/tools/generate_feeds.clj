(ns clojars.tools.generate-feeds
  (:gen-class)
  (:require
   [clojars.config :refer [config]]
   [clojars.db :as db]
   [clojars.file-utils :as fu]
   [clojars.maven :as maven]
   [clojars.s3 :as s3]
   [clojars.util :as util :refer [concatv]]
   [clojure.data.xml :as xml]
   [clojure.java.io :as io]
   [clojure.set :as set])
  (:import
   (java.io
    File
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

(defn- all-grouped-jars [db]
  (->> (db/all-jars db)
       (maven/sort-by-version)
       (reverse) ;; We want the most recent version first
       (group-by (juxt :group_name :jar_name))))

(defn full-feed [db]
  (let [grouped-jars (all-grouped-jars db)]
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
     (printf ">> Writing %s..." filename)
     (binding [*out* w]
       (doseq [form data]
         (out-fn form))))
   (println "DONE")
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

(def sitemap-ns "http://www.sitemaps.org/schemas/sitemap/0.9")
(xml/alias-uri 'sitemap sitemap-ns)

(defn links-list [base-url db]
  (let [;; get current domain

        base-links [;; index, projects, security, dmca
                    ""
                    "/projects"
                    "/security"
                    "/dmca"]
        group-links (into []
                          (comp
                           (map :group_name)
                           (map #(str "/groups/" %)))
                          (db/all-groups db))

        artifact-links (into []
                             (mapcat
                              (fn [[[group-id artifact-id] jars]]
                                (let [artifact-links
                                      (concat
                                       [(str "/" artifact-id)
                                        (str "/" artifact-id "/dependents")
                                        (str "/" artifact-id "/versions")]
                                       (into []
                                             (map
                                              #(str "/" artifact-id "/versions/" (:version %)))
                                             jars))]
                                  (if (= group-id artifact-id)
                                    artifact-links
                                    (map #(str "/" group-id %) artifact-links)))))
                             (all-grouped-jars db))

        user-links (into []
                         (map :user)
                         (db/all-users db))

        all-links (concat base-links
                          group-links
                          artifact-links
                          user-links)]
    (map #(str base-url %) all-links)))

(defn write-sitemap
  "returns sitemap filename"
  [dest index links]
  (let [sitemap-file (str dest "/sitemap-" index ".xml")
        sitemap [::sitemap/urlset {:xmlns sitemap-ns}
                 (for [link links]
                   [::sitemap/url
                    [::sitemap/loc link]])]
        data [(xml/sexp-as-element sitemap)]]
    [(write-to-file data
                    sitemap-file
                    nil
                    #(xml/emit % *out*))]))

(defn write-sitemap-index
  "returns sitemap index filename"
  [base-url dest sitemap-filenames]
  (let [sitemap-index-file (str dest "/sitemap.xml")
        sitemap-index [::sitemap/sitemapindex {:xmlns sitemap-ns}
                       (for [[sitemap-filename _] sitemap-filenames
                             :let [sitemap-file (io/file sitemap-filename)]]
                         [::sitemap/sitemap
                          [::sitemap/loc (format "%s/%s"
                                                 base-url
                                                 (File/.getName sitemap-file))]])]
        data [(xml/sexp-as-element sitemap-index)]]
    [(write-to-file data
                    sitemap-index-file
                    nil
                    #(xml/emit % *out*))]))

(defn generate-sitemaps
  "base-url - without the trailing slash"
  [base-url dest db]
  (let [sitemap-files (->> (links-list base-url db)
                           (partition-all 50000)
                           (map-indexed #(write-sitemap dest %1 %2))
                           ((juxt #(write-sitemap-index base-url dest %) identity))
                           (apply concat)
                           (apply list*))
        checksum-files (mapcat write-sums sitemap-files)]
    (concat sitemap-files checksum-files)))

(defn put-files
  ([s3-bucket files]
   (put-files s3-bucket "" files))
  ([s3-bucket prefix files]
   (run! #(let [f (io/file %)]
            (printf ">> Uploading %s to S3..." (File/.getPath f))
            (s3/put-file s3-bucket (str prefix (File/.getName f)) f
                         {:ACL "public-read"})
            (println "DONE"))
         files)))

(defn generate-feed
  [dest db]
  (let [feed-file (str dest "/feed.clj.gz")]
    (concatv
     [(write-to-file (full-feed db) feed-file :gzip)]
     (write-sums feed-file))))

(defn generate-poms-list
  [dest s3-bucket]
  (let [poms (pom-list s3-bucket)
        poms-file (str dest "/all-poms.txt")
        poms-gz-file (str poms-file ".gz")]
    (concatv
     [(write-to-file poms poms-file nil println)]
     [(write-to-file poms poms-gz-file :gzip println)]
     (write-sums poms-file)
     (write-sums poms-gz-file))))

(defn generate-jars-list
  [dest db]
  (let [jars (jar-list db)
        jars-file (str dest "/all-jars.clj")
        jars-gz-file (str jars-file ".gz")]
    (concatv
     [(write-to-file jars jars-file nil)]
     [(write-to-file jars jars-gz-file :gzip)]
     (write-sums jars-file)
     (write-sums jars-gz-file))))

(defn generate+store-feed
  [db s3-client feed-dir]
  (put-files s3-client
             (generate-feed feed-dir db)))

(defn generate+store-poms-list
  [s3-client feed-dir]
  (put-files s3-client
             (generate-poms-list feed-dir s3-client)))

(defn generate+store-jars-list
  [db s3-client feed-dir]
  (put-files s3-client
             (generate-jars-list feed-dir db)))

(defn generate+store-sitemaps
  [db s3-client feed-dir base-url]
  (put-files s3-client "sitemap/"
             (generate-sitemaps base-url feed-dir db)))

(defn -main [feed-dir env]
  (let [{:keys [db s3 base-url]} (config (keyword env))
        repo-s3-client (s3/s3-client (:repo-bucket s3))
        stats-s3-client (s3/s3-client (:stats-bucket s3))]
    (println "Generating feed...")
    (generate+store-feed db repo-s3-client feed-dir)
    (println "Generating poms list...")
    (generate+store-poms-list repo-s3-client feed-dir)
    (println "Generating jars list...")
    (generate+store-jars-list db repo-s3-client feed-dir)
    (println "Generating sitemaps...")
    (generate+store-sitemaps db stats-s3-client feed-dir base-url)
    (println "DONE")))
