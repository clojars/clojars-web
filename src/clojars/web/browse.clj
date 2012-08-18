(ns clojars.web.browse
  (:require [clojars.web.common :refer [html-doc jar-link user-link format-date]]
            [clojars.db :refer [browse-projects]]
            [hiccup.core :refer [h]]))

 (defn browse [account params] 
   (html-doc account "All projects"
     [:h1 "All projects"]
     [:ul
      (for [jar (browse-projects)]
        [:li.browse-results
          (jar-link jar) " " (h (:version jar))
          [:br]
          (when (seq (:description jar))
            [:span.desc (h (:description jar))
              [:br]])
           [:span.details 
             (user-link (:user jar)) 
             " "
             (if-let [created (:created jar)]
               [:td (format-date created)])]])]))
