(ns clojars.search
  (:refer-clojure :exclude [index])
  (:require
   [clojars.config :refer [config]]
   [clojars.db :as db]
   [clojars.maven :as maven]
   [clojars.stats :as stats]
   [clojure.set :as set]
   [clojure.string :as str]
   [com.stuartsierra.component :as component])
  (:import
   (java.nio.file
    Paths)
   (java.time
    ZonedDateTime)
   (java.util
    Date)
   (org.apache.lucene.analysis
    Analyzer
    Analyzer$TokenStreamComponents
    LowerCaseFilter)
   (org.apache.lucene.analysis.core
    KeywordAnalyzer
    WhitespaceTokenizer)
   (org.apache.lucene.analysis.miscellaneous
    PerFieldAnalyzerWrapper)
   (org.apache.lucene.document
    Document
    DoubleDocValuesField
    Field$Store
    StringField
    TextField)
   (org.apache.lucene.index
    DirectoryReader
    IndexReader
    IndexWriter
    IndexWriterConfig
    IndexWriterConfig$OpenMode
    Term)
   (org.apache.lucene.queries.function
    FunctionScoreQuery)
   (org.apache.lucene.queryparser.flexible.standard
    StandardQueryParser)
   (org.apache.lucene.search
    DoubleValuesSource
    IndexSearcher
    ScoreDoc
    TopDocs)
   (org.apache.lucene.search.similarities
    BM25Similarity)
   (org.apache.lucene.store
    Directory
    NIOFSDirectory)))

(set! *warn-on-reflection* true)

(defprotocol Search
  (index! [t pom])
  (search [t query page])
  (delete!
    [t group-id]
    [t group-id artifact-id]))

(def ^:private renames
  {:name       :artifact-id
   :jar_name   :artifact-id
   :group      :group-id
   :group_name :group-id
   :created    :at
   :homepage   :url})

(defn- doc-id ^String [group-id artifact-id]
  (str group-id ":" artifact-id))

(defn- jar->id ^String [{:keys [artifact-id group-id]}]
  (doc-id group-id artifact-id))

(defn delete-from-index [^IndexWriter index-writer ^String group-id & [artifact-id]]
  (let [term (if artifact-id
               (Term. "id" (doc-id group-id artifact-id))
               (Term. "group-id" group-id))]
    (.deleteDocuments index-writer
                      ^"[Lorg.apache.lucene.index.Term;" (into-array [term]))))

(defn- whitespace+lowercase-analyzer
  "Returns an analyzer that splits on whitespace and converts to lowercase. We use
  a custom analyzer because:

  - StandardAnalyzer splits on hyphens, but we want to be able to match foo-bar
  - WhitespaceAnalyzer doesn't split on hyphens, but doesn't lowercase tokens either

  We do split tokens on hyphens when adding them to the index, but do that
  manually (see `hyphen-remover`)."
  []
  (proxy [Analyzer] []
    (createComponents [_fieldname]
      (let [wst (WhitespaceTokenizer.)]
        (Analyzer$TokenStreamComponents.
         wst
         (LowerCaseFilter. wst))))))

(defn indexing-analyzer []
  (PerFieldAnalyzerWrapper.
   (whitespace+lowercase-analyzer)
   {"artifact-id" (KeywordAnalyzer.)
    "group-id"    (KeywordAnalyzer.)
    "at"          (KeywordAnalyzer.)}))

(defn- no-len-similarity
  "Used to override the default similarity to ignore the length of a document when scoring.
  1.2 is the default k1. Setting b to 0 (instead of the default of 0.75) forces
  the doc length to be ignored."
  []
  (BM25Similarity. 1.2 0))

(defn- index-writer ^IndexWriter [index create?]
  (IndexWriter.
   index
   (doto (IndexWriterConfig. (indexing-analyzer))
     (.setOpenMode (if create?
                     IndexWriterConfig$OpenMode/CREATE
                     IndexWriterConfig$OpenMode/CREATE_OR_APPEND))
     (.setSimilarity (no-len-similarity)))))

(defn- index-reader
  ^IndexReader [^Directory index]
  (DirectoryReader/open index))

(defn- hyphen-remover
  "Replaces hyphens with spaces. This is used to expand a token into its
  components for use in the full content string. Should be used alongside the
  unexpanded token so it is still searchable."
  [f]
  (fn [m]
    (when-some [v (f m)]
      (str/replace v #"[-]" " "))))

(defn- period-remover
  "Replaces periods with spaces. This is used to expand a token into its
  components for use in the full content string. Should be used alongside the
  unexpanded token so it is still searchable."
  [f]
  (fn [m]
    (when-some [v (f m)]
      (str/replace v #"[.]" " "))))

(defn- sentence-period-remover
  "Removes periods at the end of sentences since the whitespace tokenizer won't."
  [f]
  (fn [m]
    (when-some [v (f m)]
      (str/replace v #"\.(\s|$)" " "))))

(def ^:private content-items
  [:artifact-id
   (hyphen-remover :artifact-id)
   :group-id
   (hyphen-remover :group-id)
   ;; Include 'group name' & 'group name/artifact-name' in content (for a
   ;; group-id of group.name) to aid in searching for things where new projects
   ;; had to be deployed under a domain-based group
   (period-remover :group-id)
   (period-remover #(->> % ((juxt :group-id :artifact-id)) (str/join "/")))
   ;; Include 'group-name/artifact-name' in content to allow
   ;; the "group-name/artifact-name" phrase to find it
   #(->> % ((juxt :group-id :artifact-id)) (str/join "/"))
   (sentence-period-remover :description)
   :url
   :version
   #(->> % :authors (str/join " "))])

(def ^:private ^String content-field-name "_content")
(def ^:private ^String boost-field-name "_download_boost")

;; *StringField* is indexed but not tokenized, term freq. or positional info not indexed
(defn- string-field
  [^String name ^String value]
  (StringField. name value Field$Store/YES))

;; Indexed & tokenized
(defn- text-field
  [^String name ^String value]
  (TextField. name value Field$Store/YES))

(defn jar->doc
  ^Iterable [{:keys [at
                     artifact-id
                     group-id
                     description
                     licenses
                     url
                     version]
              :or   {at (Date.)}
              :as   jar}
             download-boost]
  (doto (Document.)
    ;; id: We need a unique identifier for each doc so that we can use updateDocument
    (.add (string-field "id" (jar->id jar)))
    (.add (string-field "artifact-id" artifact-id))
    (.add (string-field "group-id" group-id))
    (cond-> description
      (.add (text-field "description" description)))
    (.add (string-field "at" (str (.getTime ^Date at))))
    (.add (text-field "licenses" (str/join " " (map :name licenses))))
    (cond-> url
      (.add (string-field "url" url)))
    ;; version isn't really useful to search, since we only store the
    ;; most-recently-seen value, but we have it here because we've had it
    ;; historically
    (cond-> version
      (.add (string-field "version" version)))
    ;; content field containing all values to use as the default search field
    (.add (text-field content-field-name
                      (str/join " " ((apply juxt content-items) jar))))
    ;; adds a boost field based on the ratio of downloads of the jar to the
    ;; total number of downloads. This is then applied to the query below.
    (.add (DoubleDocValuesField. boost-field-name download-boost))))

;; We multiply this by the fraction of total downloads an item gets to
;; compute its download score. It's an arbitrary value chosen to give
;; subjectively good search results.
;;
;; The most downloaded item has about 3% of the total downloads, so
;; the maximum score is about 50 * 0.03 = 1.5.

(def download-score-weight 50)

(defn- calculate-document-boost
  [stats {:as _jar :keys [artifact-id group-id]}]
  (let [total (stats/total-downloads stats)]
    (* download-score-weight
       (/ (or (stats/download-count stats group-id artifact-id) 0)
          (max 1 total)))))

(defn disk-index
  [index-path]
  (NIOFSDirectory/open
   (Paths/get index-path (into-array String nil))))

(defn- index-jar
  [^IndexWriter index-writer stats jar]
  (let [jar' (set/rename-keys jar renames)
        doc (jar->doc jar' (calculate-document-boost stats jar'))]
    ;; always delete and replace the doc, since we are indexing every version
    ;; and the last one wins
    (.updateDocument index-writer (Term. "id" (jar->id jar')) doc)))

(defn- track-index-status
  [{:keys [indexed last-time] :as status}]
  (let [status' (update status :indexed inc)]
    (if (= 0 (rem indexed 1000))
      (let [next-time (System/currentTimeMillis)]
        (printf "Indexed %s jars (%f/second)\n" indexed (float (/ (* 1000 1000) (- next-time last-time))))
        (flush)
        (assoc status' :last-time next-time))
      status')))

(defn generate-index [db stats]
  (let [index-path ((config) :index-path)]
    (printf "index-path: %s\n" index-path)
    (with-open [index-writer (index-writer (disk-index index-path) true)]
      (let [{:keys [indexed start-time]}
            (reduce
             (fn [status jar]
               (try
                 (index-jar index-writer stats jar)
                 (catch Exception e
                   (printf "Failed to index %s/%s:%s - %s\n"
                           (:group_name jar) (:jar_name jar) (:version jar)
                           (.getMessage e))
                   (.printStackTrace e)))
               (track-index-status status))
             {:indexed 0
              :last-time (System/currentTimeMillis)
              :start-time (System/currentTimeMillis)}
             (maven/sort-by-version (db/all-jars db)))
            seconds (float (/ (- (System/currentTimeMillis) start-time) 1000))]
        (printf "Indexing complete. Indexed %s jars in %f seconds (%f/second)\n"
                indexed seconds (/ indexed seconds))
        (flush)))))

(defn date-in-epoch-ms
  [iso-8601-date-string]
  (-> (ZonedDateTime/parse iso-8601-date-string)
      .toInstant
      .toEpochMilli
      str))

(defn lucene-time-syntax
  [start-time end-time]
  (format "at:[%s TO %s]"
          (date-in-epoch-ms start-time)
          (date-in-epoch-ms end-time)))

(defn replace-time-range
  "Replaces human readable time range in query with epoch milliseconds"
  ^String [query]
  (let [matches (re-find #"at:\[(.*) TO (.*)\]" query)]
    (if (or (nil? matches) (not= (count matches) 3))
      query
      (try (->> (lucene-time-syntax (nth matches 1) (nth matches 2))
                (str/replace query (nth matches 0)))
           (catch Exception _ query)))))

(defn- parse-doc
  [^Document doc score]
  {:artifact-id (.get doc "artifact-id")
   :at          (.get doc "at")
   :group-id    (.get doc "group-id")
   :description (.get doc "description")
   :version     (.get doc "version")
   :score       score})

(defn- expand-group+artifact
  "Converts 'foo/bar' into an additional clause for group & artifact exactly.
  \"foo/bar\" (a phrase instead of a term) is left alone."
  [query]
  ;; This isn't perfect, it will collapse " foo " to " foo ", but
  ;; leading/trailing whitespace likely won't be used in search phrases. This
  ;; will also convert " foo/bar" into " group-id:foo AND artifact-id:bar",
  ;; which won't match anything.
  (->> (str/split query #"\s+")
       (map #(if-some [[_ group artifact] (re-find #"^([^\"\s]+)/([^\"\s]+)$" %)]
               (format "((group-id:%s AND artifact-id:%s) OR \"%s/%s\")" group artifact group artifact)
               %))
       (str/join " ")))

(def ^:private adjust-query
  (comp
   replace-time-range
   expand-group+artifact))

(defn -search*
  [^IndexReader index-reader query limit]
  (let [searcher      (doto (IndexSearcher. index-reader)
                        (.setSimilarity (no-len-similarity)))
        parser        (StandardQueryParser. (whitespace+lowercase-analyzer))
        parsed-query  (.parse parser ^String (adjust-query query) content-field-name)
        boosted-query (FunctionScoreQuery/boostByValue
                       parsed-query
                       (DoubleValuesSource/fromDoubleField boost-field-name))
        hits          (.search searcher boosted-query ^long limit)]
    {:hits     hits
     :searcher searcher
     :query    boosted-query}))

;; http://stackoverflow.com/questions/963781/how-to-achieve-pagination-in-lucene
(defn -search
  [index query page]
  (if (empty? query)
    []
    (with-open [index-reader (index-reader index)]
      (let [per-page 24
            offset (* per-page (- page 1))

            {:keys [^TopDocs hits ^IndexSearcher searcher]}
            (-search* index-reader query (* per-page page))

            results (for [^ScoreDoc hit (take per-page (drop offset (.scoreDocs hits)))]
                      (parse-doc (.doc searcher (.doc hit)) (.score hit)))]
        (doall
         (with-meta results
           {:total-hits       (.-value (.totalHits hits))
            :max-score        (reduce max 0 (map :score results))
            :results-per-page per-page
            :offset           offset}))))))

(defn explain-top-n
  "Debugging function to print out the score and its explanation of the
   top `n` matches for the given query.
   "
  ([search-comp query] (explain-top-n 5 search-comp query))
  ([n {:keys [index]} query]
   (with-open [index-reader (index-reader index)]
     (let [{:keys [^TopDocs hits ^IndexSearcher searcher query]}
           (-search* index-reader query 24)]
       (println query)
       (run!
        (fn [^ScoreDoc sd]
          (println
           (.get (.doc searcher (.-doc sd)) "id")
           (.explain searcher query (.-doc sd))))
        (take n (.scoreDocs hits)))))))

(defrecord LuceneSearch [stats index-factory ^Directory index]
  Search
  (index! [_t pom]
    (with-open [index-writer (index-writer index false)]
      (index-jar index-writer stats pom)))
  (search [_t query page]
    (-search index query page))
  (delete! [_t group-id]
    (with-open [index-writer (index-writer index false)]
      (delete-from-index index-writer group-id)))
  (delete! [_t group-id artifact-id]
    (with-open [index-writer (index-writer index false)]
      (delete-from-index index-writer group-id artifact-id)))
  component/Lifecycle
  (start [t]
    (if index
      t
      (assoc t :index (index-factory))))
  (stop [t]
    (when index
      (.close index))
    (assoc t :index nil)))

(defn lucene-component []
  (map->LuceneSearch {}))
