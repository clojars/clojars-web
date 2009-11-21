(ns clojars.web.search
  (:use clojars.web.common
        clojars.db
        compojure))

(defn search [account query]
  (html-doc account (str (h query) " - search")
    [:h1 "Search for " (h query)]
    [:ul
     (for [jar (search-jars query)]
       [:li {:class "search-results"}
        (jar-link jar) " " (h (:version jar))
        [:span {:class "desc"} " &mdash; "
         (h (:description jar))]])]))
