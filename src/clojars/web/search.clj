(ns clojars.web.search
  (:use clojars.web.common
        clojars.db
        compojure))

(defn format-date [s]
  (.format (java.text.SimpleDateFormat. "yyyy-MM-dd") s))

(defn search [account query]
  (html-doc account (str (h query) " - search")
    [:h1 "Search for " (h query)]
    [:ul
     (for [jar (search-jars query)
           ;; bc too lazy to see why blank entries are showing up
           :when (not (nil? (:jar_name jar)))]
       [:li.search-results
        (jar-link jar) " " (h (:version jar))
        [:br]
        (let [description (:description jar)]
          (when (and (not= "" description)
                     (not (nil? description)))
            [:span.desc (h description)
             [:br]]))
        [:span.details
         (user-link (:user jar))
         " "
         (if-let [created (:created jar)]
           [:td (format-date created)])]])]))



