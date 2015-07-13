(ns clojars.web.search
  (:require [clojars.web.common :refer [html-doc jar-link jar-fork?
                                        collection-fork-notice user-link
                                        format-date]]
            [clojars.search :as search]
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
  (let [results (search/search query)]
    (json/generate-string {:count (count results)
                           :results (map jar->json results)})))

(defn json-search [query]
  {:status 200,
   :headers {"Content-Type" "application/json; charset=UTF-8"}
   :body (json-gen query)})

(defn html-search [account query]
  (html-doc account (str query " - search")
    [:div.light-article.row
     [:h1 "Search for '" query "'"]
     (try
       (let [results (search/search query)]
         (if (empty? results)
           [:p "No results."]
           [:div
            (if (some jar-fork? results)
              collection-fork-notice)
            [:ul.row
             (for [jar results]
               [:li.search-results.col-md-4.col-lg-3.col-sm-6.col-xs-12
                [:div.result
                 (jar-link {:jar_name (:artifact-id jar)
                            :group_name (:group-id jar)}) " " (:version jar)
                 [:br]
                 (when (seq (:description jar))
                   [:span.desc (:description jar)
                    [:br]])
                 [:span.details (if-let [created (:created jar)]
                                  [:td (format-date created)])]]])]]))
       (catch Exception _
         [:p "Could not search; please check your query syntax."]))]))

(defn search [account params]
  (let [q (params :q)]
    (if (= (params :format) "json")
      (json-search q)
      (html-search account q))))
