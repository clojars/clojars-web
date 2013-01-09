(ns clojars.search
  (:refer-clojure :exclude [index])
  (:require [clucy.core :as clucy]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.set :as set]
            [clojars.config :refer [config]]
            [clojars.maven :as mvn])
  (:import (org.apache.lucene.analysis PerFieldAnalyzerWrapper)
           (org.apache.lucene.analysis.standard StandardAnalyzer)
           (org.apache.lucene.analysis KeywordAnalyzer)))

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

(defn index-pom [index pom-file]
  (let [pom (-> (mvn/pom-to-map pom-file)
                (set/rename-keys renames)
                (update-in [:licenses] #(mapv (comp :name bean) %)))
        ;; TODO: clucy forces its own :_content on you
        content (string/join " " ((apply juxt content-fields) pom))
        doc (assoc (dissoc pom :homepage :dependencies :scm)
              :at (.lastModified pom-file)
              :_content content)]
    (binding [clucy/*analyzer* analyzer]
      (clucy/search-and-delete index (format "artifact-id:%s AND group-id:%s"
                                             (:artifact-id pom)
                                             (:group-id pom)))
      (clucy/add index (with-meta doc field-settings)))))

(defn index-repo [root]
  (with-open [index (clucy/disk-index (config :index-path))]
    (doseq [file (file-seq (io/file root))
            :when (.endsWith (str file) ".pom")]
      (index-pom index file))))

(defn search [query]
  (if (empty? query)
    []
    (with-open [index (clucy/disk-index (config :index-path))]
      (binding [clucy/*analyzer* analyzer]
        (clucy/search index query 25)))))

(defn -main [& [repo]]
  (index-repo (or repo (config :repo))))
