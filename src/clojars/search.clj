(ns clojars.search
  (:refer-clojure :exclude [index])
  (:require [clojars
             [config :refer [config]]
             [stats :as stats]]
            [clojars.db :as db]
            [clojure
             [set :as set]
             [string :as string]]
            [clucy.core :as clucy]
            [com.stuartsierra.component :as component])
  (:import [org.apache.lucene.analysis KeywordAnalyzer PerFieldAnalyzerWrapper]
           org.apache.lucene.analysis.standard.StandardAnalyzer
           org.apache.lucene.index.IndexNotFoundException
           org.apache.lucene.queryParser.QueryParser
           org.apache.lucene.search.IndexSearcher
           [org.apache.lucene.search.function CustomScoreQuery DocValues FieldCacheSource ValueSourceQuery]))

(defprotocol Search
  (index! [t pom])
  (search [t query page])
  (delete!
    [t group-id]
    [t group-id artifact-id]))


(def content-fields [:artifact-id :group-id :version :description
                     :url #(->> % :authors (string/join " "))])

(def field-settings {:artifact-id {:analyzed false}
                     :group-id {:analyzed false}
                     :version {:analyzed false}
                     :at {:analyzed false}})

;; TODO: make this easy to do from clucy
(defonce analyzer (let [a (PerFieldAnalyzerWrapper.
                           ;; Our default analyzer has no stop words.
                           (StandardAnalyzer. clucy/*version* #{}))]
                    (doseq [[field {:keys [analyzed]}] field-settings
                            :when (false? analyzed)]
                      (.addAnalyzer a (name field) (KeywordAnalyzer.)))
                    a))

(def renames {:name       :artifact-id
              :jar_name   :artifact-id
              :group      :group-id
              :group_name :group-id
              :created    :at
              :homepage   :url})

(defn delete-from-index [index group-id & [artifact-id]]
  (binding [clucy/*analyzer* analyzer]
    (clucy/search-and-delete index
                             (cond-> (str "group-id:" group-id)
                                     artifact-id (str " AND artifact-id:" artifact-id)))))

(defn update-if-exists
  [m k f & args]
  (if (contains? m k)
    (apply update m k f args)
    m))

(defn index-jar [index jar]
  (let [jar' (-> jar
                (set/rename-keys renames)
                (update :licenses #(mapv :name %))
                (update-if-exists :at (memfn getTime)))
        ;; TODO: clucy forces its own :_content on you
        content (string/join " " ((apply juxt content-fields) jar'))
        doc (assoc (dissoc jar' :dependencies :scm)
                   :_content content)]
    (binding [clucy/*analyzer* analyzer]
      (let [[old] (try
                    (clucy/search index (format "artifact-id:%s AND group-id:%s"
                                                (some-> doc :artifact-id (QueryParser/escape))
                                                (some-> doc :group-id (QueryParser/escape))) 1)
                    (catch IndexNotFoundException _
                      ;; This happens when the index is searched before any data
                      ;; is added. We can treat it here as a nil return
                      ))]
        (if old
          (when (< (Long. (:at old)) (:at doc))
            (clucy/search-and-delete index (format "artifact-id:%s AND group-id:%s"
                                                   (some-> doc :artifact-id (QueryParser/escape))
                                                   (some-> doc :group-id (QueryParser/escape))))
            (clucy/add index (with-meta doc field-settings)))
          (clucy/add index (with-meta doc field-settings)))))))

(defn- track-index-status
  [{:keys [indexed last-time] :as status}]
  (let [status' (update status :indexed inc)]
    (if (= 0 (rem indexed 1000))
        (let [next-time (System/currentTimeMillis)]
          (printf "Indexed %s jars (%f/second)\n" indexed (float (/ (* 1000 1000) (- next-time last-time))))
          (flush)
          (assoc status' :last-time next-time))
        status')))

(defn generate-index [db]
  (let [index-path ((config) :index-path)]
    (printf "index-path: %s\n" index-path)
    (with-open [index (clucy/disk-index index-path)]
      ;; searching with an empty index creates an exception
      (clucy/add index {:dummy true})
      (let [{:keys [indexed start-time]}
            (reduce
              (fn [status jar]
                (try
                  (index-jar index jar)
                  (catch Exception e
                    (printf "Failed to index %s/%s:%s - %s\n" (:group_name jar) (:jar_name jar) (:version jar)
                            (.getMessage e))
                    (.printStackTrace e)))
                (track-index-status status))
              {:indexed 0
               :last-time (System/currentTimeMillis)
               :start-time (System/currentTimeMillis)}
              (db/all-jars db))
            seconds (float (/ (- (System/currentTimeMillis) start-time) 1000))]
        (printf "Indexing complete. Indexed %s jars in %f seconds (%f/second)\n"
                indexed seconds (/ indexed seconds))
        (flush))
      (clucy/search-and-delete index "dummy:true"))))


;; We multiply this by the fraction of total downloads an item gets to
;; compute its download score. It's an arbitrary value chosen to give
;; subjectively good search results.
;;
;; The most downloaded item has about 3% of the total downloads, so
;; the maximum score is about 50 * 0.03 = 1.5.

(def download-score-weight 50)

(defn download-values [stats]
  (let [total (stats/total-downloads stats)]
    (ValueSourceQuery.
     (proxy [FieldCacheSource] ["download-count"]
       (getCachedFieldValues [cache _ reader]
         (let [ids (map vector
                        (.getStrings cache reader "group-id")
                        (.getStrings cache reader "artifact-id"))
               download-score (fn [i]
                                (let [score
                                      (inc
                                         (* download-score-weight
                                            (/ (apply
                                                (comp inc stats/download-count)
                                                stats
                                                (nth ids i))
                                               (max 1 total))))]
                                  score))]
           (proxy [DocValues] []
             (floatVal [i]
               (download-score i))
             (intVal [i]
               (download-score i))
             (toString [i]
               (str "download-count="
                    (download-score i))))))))))

(defn date-in-epoch-ms
  [iso-8601-date-string]
  (-> (java.time.ZonedDateTime/parse iso-8601-date-string)
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
  [query]
  (let [matches (re-find #"at:\[(.*) TO (.*)\]" query)]
    (if (or (nil? matches) (not= (count matches) 3))
      query
      (try (->> (lucene-time-syntax (nth matches 1) (nth matches 2))
                (string/replace query (nth matches 0)))
           (catch Exception _ query)))))

; http://stackoverflow.com/questions/963781/how-to-achieve-pagination-in-lucene
(defn -search [stats index query page]
  (if (empty? query)
    []
    (binding [clucy/*analyzer* analyzer]
      (with-open [searcher (IndexSearcher. index)]
        (let [per-page 24
              offset (* per-page (- page 1))
              parser (QueryParser. clucy/*version*
                                   "_content"
                                   clucy/*analyzer*)
              query  (.parse parser (replace-time-range query))
              query  (CustomScoreQuery. query (download-values stats))
              hits   (.search searcher query (* per-page page))
              highlighter (#'clucy/make-highlighter query searcher nil)]
          (doall
           (let [dhits (take per-page (drop offset (.scoreDocs hits)))]
             (with-meta (for [hit dhits]
                          (#'clucy/document->map
                           (.doc searcher (.doc hit))
                           (.score hit)
                           highlighter))
               {:total-hits (.totalHits hits)
                :max-score (.getMaxScore hits)
                :results-per-page per-page
                :offset offset}))))))))

(defrecord LuceneSearch [stats index-factory index]
  Search
  (index! [t pom]
    (index-jar index pom))
  (search [t query page]
    (-search stats index query page))
  (delete! [t group-id]
    (delete-from-index index group-id))
  (delete! [t group-id artifact-id]
    (delete-from-index index group-id artifact-id))
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
