(ns clojars.web.browse
  (:require [clojars.web.common :refer [html-doc jar-link user-link format-date
                                        page-nav page-description jar-name
                                        collection-fork-notice]]
            [clojars.db :refer [browse-projects count-all-projects
                                count-projects-before]]
            [hiccup.form :refer [label submit-button text-field submit-button]]
            [ring.util.response :refer [redirect]]))

(defn browse-page [db account page per-page]
  (let [project-count (count-all-projects db)
        total-pages (-> (/ project-count per-page) Math/ceil .intValue)
        projects (browse-projects db page per-page)]
    (html-doc account "All projects"
     [:div.light-article.row
      [:h1 "All projects"]
      [:div.small-section
       [:form.browse-from {:method :get :action "/projects"}
        (label :from "Enter a few letters...")
        (text-field {:placeholder "Enter a few letters..."
                     :required true}
                    :from)
        (submit-button {:id :jump} "Jump")]]
      collection-fork-notice
      (page-description page per-page project-count)
      [:ul.row
       (for [[i jar] (map-indexed vector projects)]
         [:li.col-md-4.col-lg-3.col-sm-6.col-xs-12
          [:div.result
           [:a {:name i}]
           (jar-link jar) " " (:version jar)
           [:br]
           (if (seq (:description jar))
             [:span.desc (:description jar)]
             [:span.hint "No description given"])
           [:hr]
           [:span.details
            (user-link (:user jar))
            " "
            (if-let [created (:created jar)]
              [:td (format-date created)])]]])]
      (page-nav page total-pages)])))

(defn browse [db account params]
  (let [per-page 20]
    (if-let [from (:from params)]
      (let [i (count-projects-before db from)
            page (inc (int (/ i per-page)))]
        (redirect (str "/projects?page=" page "#" (mod i per-page))))
      (browse-page db account (Integer. (or (:page params) 1)) per-page))))
