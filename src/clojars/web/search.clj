(ns clojars.web.search
  (:require [clojars.web.common :refer [html-doc jar-link user-link format-date]]
            [clojars.search :as search]
            [hiccup.core :refer [h]]
            [cheshire.core :as json]))

(defn- jar->json [jar]
  (let [m {:jar_name (:artifact-id jar)
           :group_name (:group-id jar)
           :version (:version jar)
           :description (:description jar)}
        created (:at jar)]
    (if created
      (assoc m :created created)
      m)))

(defn json-gen [query]
  (let [results (filter #(not (nil? (:jar_name %)))
                        (search/search query))]
    (json/generate-string {:count (count results)
                           :results (map jar->json results)})))

(defn json-search [query]
  {:status 200,
   :headers {"Content-Type" "application/json; charset=UTF-8"}
   :body (json-gen query)})

(defn html-search [account query]
  (html-doc account (str (h query) " - search")
    [:h1 "Search for " (h query)]
    [:ul
     (try
       (let [results (search/search query)]
         (if (empty? results)
           [:p "No results."]
           (for [jar results
                 ;; bc too lazy to see why blank entries are showing up
                 :when (not (nil? (:artifact-id jar)))]
             [:li.search-results
              (jar-link {:jar_name (:artifact-id jar)
                         :group_name (:group-id jar)}) " " (h (:version jar))
              [:br]
              (when (seq (:description jar))
                [:span.desc (h (:description jar))
                 [:br]])
              [:span.details (if-let [created (:created jar)]
                               [:td (format-date created)])]])))
       (catch Exception _
         [:p "Could not search; please check your query syntax."]))]))

(defn search [account params]
  (let [q (params :q)]
    (if (= (params :format) "json")
      (json-search q)
      (html-search account q))))
