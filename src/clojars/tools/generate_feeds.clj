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
   [clojure.data.xml :as xml]
   [clojure.set :as set]
   [clojars.routes.artifact :as artifact])
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

(def sitemap-ns "http://www.sitemaps.org/schemas/sitemap/0.9")
(xml/alias-uri 'sitemap sitemap-ns)

(defn links-list [base-url db]
  (let [ ;; get current domain

        base-links [ ;; index, projects, security, dmca
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
  (let [sitemap-file (str dest "/sitemap-" index ".xml.gz")
        sitemap [::sitemap/urlset {:xmlns sitemap-ns}
                 (for [link links]
                   [::sitemap/url
                    [::sitemap/loc link]])]]
    (write-to-file [(xml/sexp-as-element sitemap)]
                   sitemap-file
                   :gzip
                   #(xml/emit % *out*))))

(defn write-sitemap-index
  "returns sitemap index filename"
  [base-url dest sitemap-files]
  (let [sitemap-index-file (str dest "/sitemap-index.xml.gz")
        sitemap-index [::sitemap/sitemapindex {:xmlns sitemap-ns}
                       (for [sitemap-file sitemap-files]
                         [::sitemap/sitemap
                          [::sitemap/loc (str base-url "/" sitemap-file)]])]]
    (write-to-file [(xml/sexp-as-element sitemap-index)]
                   sitemap-index-file
                   :gzip
                   #(xml/emit % *out*))))

(defn generate-sitemaps
  "base-url - without the trailing slash"
  [base-url dest db]
  (let [sitemap-files (->> (links-list base-url db)
                           (partition-all 50000)
                           (map-indexed #(write-sitemap dest %1 %2))
                           ((juxt #(write-sitemap-index base-url dest %) identity))
                           (apply list*))
        checksum-files (mapcat write-sums sitemap-files)]
    (concat sitemap-files checksum-files)))

(defn put-files [s3-bucket & files]
  (run! #(let [f (io/file %)]
           (s3/put-file s3-bucket (.getName f) f {:ACL "public-read"}))
        files))

(defn generate-feeds [dest base-url db s3-bucket]
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
            (write-sums gz-file))))

  (apply put-files
         s3-bucket
         (generate-sitemaps base-url dest db)))

(defn -main [feed-dir env]
  (let [{:keys [db s3 base-url]} (config (keyword env))]
    (generate-feeds feed-dir
                    base-url
                    db
                    (s3/s3-client (:repo-bucket s3)))))
