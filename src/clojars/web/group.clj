(ns clojars.web.group
  (:require [clojars.web.common :refer [html-doc jar-link user-link error-list]]
            [clojars.db :refer [jars-by-groupname]]
            [clojars.auth :refer [authorized?]]
            [hiccup.element :refer [unordered-list]]
            [hiccup.form :refer [text-field submit-button hidden-field]]
            [clojars.web.safe-hiccup :refer [form-to]]
            [clojars.web.structured-data :as structured-data]))

(defn is-admin?
  [active]
  (= 1 (:admin active)))

(defn show-group [db account groupname actives & errors]
  (let [admin? (authorized? db account groupname)]
      (html-doc (str groupname " group") {:account account :description (format "Clojars projects in the %s group" groupname)}
        [:div.small-section.col-xs-12.col-sm-6
         (structured-data/breadcrumbs [{:url  (str "https://clojars.org/groups/" groupname)
                                        :name groupname}])
         [:h1 (str groupname " group")]
         [:h2 "Projects"]
         (unordered-list (map jar-link (jars-by-groupname db groupname)))
         [:h2 "Members"]
         [:table
          [:thead
           [:tr [:th "Username"] [:th "Type"] (when admin? '([:th "Change"] [:th "Remove"]))]]
          [:tbody
           (for [active (sort-by :user actives)]
             [:tr
              [:td (user-link (:user active))]
              [:td (if (is-admin? active)
                     "Admin"
                     "Member")]
              (when admin?
                (list
                  [:td (cond
                         (= account (:user active)) ""
                         (is-admin? active)
                         (form-to [:post (str "/groups/" groupname)]
                                  (hidden-field "username" (:user active))
                                  (hidden-field "admin" 0)
                                  (submit-button "Make Member"))
                         :else
                         (form-to [:post (str "/groups/" groupname)]
                                  (hidden-field "username" (:user active))
                                  (hidden-field "admin" 1)
                                  (submit-button "Make Admin")))]
                  [:td (if (= account (:user active))
                         ""
                         (form-to [:delete (str "/groups/" groupname)]
                                  (hidden-field "username" (:user active))
                                  (submit-button "Remove")))]))])]]
         (error-list errors)
         (when admin?
           [:div.add-member
            (form-to [:post (str "/groups/" groupname)]
                     (text-field "username")
                     (hidden-field "admin" 0)
                     (submit-button "add member"))])
         (when admin?
           [:div.add-admin
            (form-to [:post (str "/groups/" groupname)]
                     (text-field "username")
                     (hidden-field "admin" 1)
                     (submit-button "add admin"))])])))
