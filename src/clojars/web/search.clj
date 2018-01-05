(ns clojars.web.search
  (:require [clojars.web.common :refer [html-doc jar-link jar-fork?
                                        collection-fork-notice user-link
                                        format-date page-nav flash xml-escape
                                        jar-notice maven-search-link]]
            [clojars.errors :as errors]
            [clojars.maven :as maven]
            [clojars.search :as search]
            [clojars.web.error-api :as error-api]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.xml :as xml]
            [hiccup.element :refer [link-to]]
            [ring.util.codec :refer [url-encode]]))

(defn- jar->json [jar]
  (let [m {:jar_name (:artifact-id jar)
           :group_name (:group-id jar)
           :version (:version jar)
           :description (:description jar)}
        created (:at jar)]
    (if created
      (assoc m :created created)
      m)))

(defn json-search [search query]
  (let [response {:status 200
                  :headers {"Content-Type" "application/json; charset=UTF-8"
                            "Access-Control-Allow-Origin" "*"}}]
    (try
      (assoc response
        :body (let [results (search/search search query 1)]
                (json/generate-string {:count (count results)
                                       :results (map jar->json results)})))
      (catch Exception _
        (error-api/error-api-response
         {:status 400
          :format :json
          :error-message (format "Invalid search syntax for query `%s`" query)}
         (errors/error-id))))))

(defn- jar->xml [jar]
  (let [attrs {:jar_name (:artifact-id jar)
               :group_name (:group-id jar)
               :version (:version jar)
               :description (xml-escape (:description jar))}
        created (:at jar)]
    {:tag :result :attrs (if created
                           (assoc attrs :created created)
                           attrs)}))

(defn xml-search [search query]
  (let [response {:status 200
                  :headers {"Content-Type" "text/xml; charset=UTF-8"
                            "Access-Control-Allow-Origin" "*"}}]
    (try
      (assoc response
             :body (let [results (search/search search query 1)]
                     (with-out-str
                       (xml/emit {:tag :results
                                  :attrs {:count (count results)}
                                  :content (map jar->xml results)}))))
      (catch Exception _
        (error-api/error-api-response
         {:status 400
          :format :xml
          :error-message (format "Invalid search syntax for query `%s`" query)}
         (errors/error-id))))))

(defn split-query
  "Tries to split a query into a group-id and artifact-id tuple"
  [query]
  (if (str/includes? query "/")
    (->> (str/split query #"[\w\.]+\/")
         (map str/lower-case))
    [nil (str/lower-case query)]))

(def maven-groups
  #{"org.clojure"})

(def org-clojure-artifacts
  #{"clojure"
    "clojurescript"
    "core.async"
    "tools.nrepl"
    "java.jdbc"
    "tools.logging"
    "tools.namespace"
    "clojure-contrib"})

(def other-artifacts
  ;eg {"simulant" "org.datomic"}
  {})

(def artifact-id->group-id
  (->> org-clojure-artifacts
       (map (fn [a] [a "org.clojure"]))
       (into {})
       (merge other-artifacts)))

(defn on-maven-central
  "Returns a tuple of group-id, artifact-id or false"
  [query]
  (let [[group-id artifact-id] (split-query query)]
    (cond
      (maven-groups group-id) [group-id artifact-id]
      (artifact-id->group-id artifact-id) [(artifact-id->group-id artifact-id) artifact-id]
      :default false)))

(defn html-search [search account query page]
  (html-doc (str query " - search - page " page) {:account account :query query :description (format "Clojars search results page %d for '%s'" page query)}
    [:div.light-article.row
     [:h1 (format "Search for '%s'" query)]
     (when query
       (when-let [mvn-tuple (on-maven-central query)]
         (flash "Given your search terms, you may also want to "
           (link-to (apply maven-search-link mvn-tuple) "search Maven Central")
           "."
           [:br]
           [:small "org.clojure artifacts are distributed via Maven Central instead of Clojars."])))
     [:p.search-query-syntax "For details on the search query syntax, see the "
      (link-to "http://github.com/clojars/clojars-web/wiki/Search-Query-Syntax" "guide")
      "."]
     (try
       (let [results (search/search search query page)
             {:keys [total-hits results-per-page offset]} (meta results)]
         (if (empty? results)
           [:p "No results."]
           [:div
            [:p (format "Total results: %s, showing %s - %s"
                  total-hits (inc offset) (+ offset (count results)))]
            (if (some jar-fork? results)
              collection-fork-notice)
            [:ul.row
             (for [{:keys [group-id artifact-id version created description]} results]
               [:li.search-results.col-xs-12.col-sm-6.col-md-4.col-lg-3
                [:div.result
                 (jar-link {:jar_name artifact-id
                            :group_name group-id}) " " version
                 [:br]
                 (if (seq description)
                   [:span.desc description
                    [:br]])
                 (jar-notice group-id artifact-id)
                 [:span.details
                  (if created
                    [:td (format-date created)])]]])]
            (page-nav page
              (int (Math/ceil (/ total-hits results-per-page)))
              :base-path (str "/search?q=" (url-encode query) "&page="))]))
       (catch Exception _
         [:p "Could not search; please check your query syntax."]))]))

(defn search [search account params]
  (let [q (params :q)
        page (or (params :page) 1)]
    (case (params :format)
      "json" (json-search search q)
      "xml"  (xml-search search q)
      (html-search search account q page))))
