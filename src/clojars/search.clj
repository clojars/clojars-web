(ns clojars.search
  (:refer-clojure :exclude [index])
  (:require [clojars
             [config :refer [config]]
             [maven :as mvn]
             [stats :as stats]]
            [clojure
             [set :as set]
             [string :as string]]
            [clojure.java.io :as io]
            [clucy.core :as clucy]
            [com.stuartsierra.component :as component])
  (:import [org.apache.lucene.analysis KeywordAnalyzer PerFieldAnalyzerWrapper]
           org.apache.lucene.analysis.standard.StandardAnalyzer
           org.apache.lucene.index.IndexNotFoundException
           org.apache.lucene.queryParser.QueryParser
           [org.apache.lucene.search.function CustomScoreQuery DocValues FieldCacheSource ValueSourceQuery]
           org.apache.lucene.search.IndexSearcher))

(defprotocol Search
  (index! [t pom])
  (search [t query page])
  (delete!
    [t group-id]
    [t group-id artifact-id]))


(def content-fields [:artifact-id :group-id :version :description
                     :url :authors])

(def field-settings {:artifact-id {:analyzed false}
                     :group-id {:analyzed false}
                     :version {:analyzed false}
                     :at {:analyzed false}})

;; TODO: make this easy to do from clucy
(defonce analyzer (let [a (PerFieldAnalyzerWrapper.
                           (StandardAnalyzer. clucy/*version*))]
                    (doseq [[field {:keys [analyzed]}] field-settings
                            :when (false? analyzed)]
                      (.addAnalyzer a (name field) (KeywordAnalyzer.)))
                    a))

(def renames {:name :artifact-id :group :group-id})

(defn delete-from-index [index group-id & [artifact-id]]
  (binding [clucy/*analyzer* analyzer]
    (clucy/search-and-delete index
                             (cond-> (str "group-id:" group-id)
                               artifact-id (str " AND artifact-id:" artifact-id)))))

(defn index-pom [index pom]
  (let [pom (-> pom
                (set/rename-keys renames)
                (update-in [:licenses] #(mapv (comp :name bean) %)))
        ;; TODO: clucy forces its own :_content on you
        content (string/join " " ((apply juxt content-fields) pom))
        doc (assoc (dissoc pom :homepage :dependencies :scm)
                   :_content content)]
    (binding [clucy/*analyzer* analyzer]
      (let [[old] (try
                    (clucy/search index (format "artifact-id:%s AND group-id:%s"
                                              (:artifact-id pom)
                                              (:group-id pom)) 1)
                    (catch IndexNotFoundException _
                      ;; This happens when the index is searched before any data
                      ;; is added. We can treat it here as a nil return
                      ))]
        (if old
          (when (and (< (Long. (:at old)) (:at doc))
                     (not (re-find #"-SNAPSHOT$" (:version doc))))
            (clucy/search-and-delete index (format "artifact-id:%s AND group-id:%s"
                                                   (:artifact-id doc)
                                                   (:group-id doc)))
            (clucy/add index (with-meta doc field-settings)))
          (clucy/add index (with-meta doc field-settings)))))))

(defn index-repo [root]
  (let [indexed (atom 0)]
    (with-open [index (clucy/disk-index (config :index-path))]
      ;; searching with an empty index creates an exception
      (clucy/add index {:dummy true})
      (doseq [file (file-seq (io/file root))
              :when (.endsWith (str file) ".pom")]
        (swap! indexed inc)
        (when (zero? (mod @indexed 100))
          (println "Indexed" @indexed))
        (try
          (index-pom index (assoc (mvn/pom-to-map file)
                                  :at (.lastModified file)))
          (catch Exception e
              (println "Failed to index" file " - " (.getMessage e)))))
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
              query  (.parse parser query)
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

(defn -main [& [repo]]
  (index-repo (or repo (config :repo))))

(defrecord LuceneSearch [stats index-factory index]
  Search
  (index! [t pom]
    (index-pom index pom))
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
