(ns clojars.web.browse
  (:require [clojars.web.common :refer [html-doc jar-link user-link format-date
                                        page-nav page-description jar-name
                                        collection-fork-notice]]
            [clojars.db :refer [browse-projects count-all-projects
                                count-projects-before]]
            [hiccup.form :refer [label submit-button]]
            [ring.util.response :refer [redirect]]))

(defn browse-page [account page per-page]
  (let [project-count (count-all-projects)
        total-pages (-> (/ project-count per-page) Math/ceil .intValue)
        projects (browse-projects page per-page)]
    (html-doc account "All projects"
     [:div {:class "light-article"}
      [:article.clearfix
       [:h1 "All projects"
        [:form.browse-from {:method :get :action "/projects"}
         (label :from "starting from")
         [:input {:type :text :name :from :id :from
                  :placeholder "Enter a few letters..."}]
         [:input {:type :submit :value "Jump" :id :jump}]]]
       collection-fork-notice
       (page-description page per-page project-count)
       [:ul
        (for [[i jar] (map-indexed vector projects)]
          [:li.browse-results
           [:a {:name i}]
           (jar-link jar) " " (:version jar)
           [:br]
           (when (seq (:description jar))
             [:span.desc (:description jar)
              [:br]])
           [:span.details
            (user-link (:user jar))
            " "
            (if-let [created (:created jar)]
              [:td (format-date created)])]])]
       (page-nav page total-pages)]])))

(defn browse [account params]
  (let [per-page 20]
    (if-let [from (:from params)]
      (let [i (count-projects-before from)
            page (inc (int (/ i per-page)))]
        (redirect (str "/projects?page=" page "#" (mod i per-page))))
      (browse-page account (Integer. (or (:page params) 1)) per-page))))
