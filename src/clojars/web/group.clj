(ns clojars.web.group
  (:require [clojars.web.common :refer [html-doc jar-link user-link error-list]]
            [clojars.db :refer [jars-by-groupname]]
            [clojars.auth :refer [authorized?]]
            [hiccup.element :refer [unordered-list]]
            [hiccup.form :refer [text-field submit-button hidden-field check-box]]
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
              [:td {:style "padding:0 5px"} (user-link (:user active))]
              [:td {:style "padding:0 5px"}
               (if (is-admin? active)
                     "Admin"
                     "Member")]
              (when admin?
                (list
                  [:td {:style "padding:0 5px"}
                   (cond
                         (= account (:user active)) ""
                         (is-admin? active)
                         (form-to [:post (str "/groups/" groupname)]
                                  (hidden-field "username" (:user active))
                                  (hidden-field "admin" 0)
                                  [:input {:style "font-size:14px; letter-spacing:0px; margin:5px; padding:5px" :type "submit" :value "Make Member"}])
                         :else
                         (form-to [:post (str "/groups/" groupname)]
                                  (hidden-field "username" (:user active))
                                  (hidden-field "admin" 1)
                                  [:input {:style "font-size:14px; letter-spacing:0px; margin:5px; padding:5px; background-color:green" :type "submit" :value "Make Admin"}]))]
                  [:td {:style "padding:0 5px"}
                   (if (= account (:user active))
                         ""
                         (form-to [:delete (str "/groups/" groupname)]
                                  (hidden-field "username" (:user active))
                                  [:input {:style "font-size:14px; letter-spacing:0px; margin:5px; padding:5px; background-color:red" :type "submit" :value "Remove"}]))]))])]]
         (error-list errors)
         [:h2 "Add member"]
         (when admin?
           [:div.add-member
            (form-to [:post (str "/groups/" groupname)]
                     (text-field "username")
                     [:div {:class "checkbox"}
                      [:label
                       [:input {:type "checkbox"
                                :name "admin"
                                :id "admin"
                                :value 1
                                :style "width:auto;margin-right:5px"
                                :checked false}]
                       "admin?"]]
                     (submit-button "add member"))])])))
