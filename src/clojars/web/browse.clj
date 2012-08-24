(ns clojars.web.browse
  (:require [clojars.web.common :refer [html-doc jar-link user-link format-date
                                        page-nav page-description]]
            [clojars.db :refer [browse-projects count-all-projects]]
            [hiccup.core :refer [h]]))

 (defn browse [account params]
   (let [page (Integer. (or (:page params) 1))
         per-page 20
         project-count (count-all-projects)
         total-pages (->  (/ project-count per-page) Math/ceil .intValue)]
   (html-doc account "All projects"
     [:h1 "All projects"]
     (page-description page per-page project-count)
     [:ul
      (for [jar (browse-projects page)]
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
               [:td (format-date created)])]])]
     (page-nav page total-pages))))
