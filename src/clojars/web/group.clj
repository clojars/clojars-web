(ns clojars.web.group
  (:require [clojars.web.common :refer [html-doc jar-link user-link error-list]]
            [clojars.db :refer [jars-by-group-id]]
            [clojars.auth :refer [authorized-admin? authorized-member?]]
            [hiccup.element :refer [unordered-list]]
            [hiccup.form :refer [text-field submit-button hidden-field check-box]]
            [clojars.web.safe-hiccup :refer [form-to]]
            [clojars.web.structured-data :as structured-data]))

(defn is-admin?
  [active]
  (= 1 (:admin active)))

(defn show-group [db account group-id actives & errors]
  (let [admin? (authorized-admin? db account group-id)
        member? (authorized-member? db account group-id)]
      (html-doc (str group-id " group") {:account account :description (format "Clojars projects in the %s group" group-id)}
        [:div.small-section.col-xs-12.col-sm-6
         (structured-data/breadcrumbs [{:url  (str "https://clojars.org/groups/" group-id)
                                        :name group-id}])
         [:h1 (str group-id " group")]
         [:h2 "Projects"]
         (unordered-list (map jar-link (jars-by-group-id db group-id)))
         [:h2 "Members"]
         (if (or admin? member?)
           [:table.group-member-list
            [:thead
             [:tr
              [:th "Username"]
              [:th "Admin?"]]]
            [:tbody
             (for [active (sort-by :user actives)]
               [:tr
                [:td (user-link (:user active))]
                [:td 
                 (if (is-admin? active)
                   "Yes"
                   "No")]
                (when admin?
                  (list
                    [:td
                     (cond
                       (= account (:user active)) ""
                       (is-admin? active)
                       (form-to [:post (str "/groups/" group-id)]
                         (hidden-field "username" (:user active))
                         (hidden-field "admin" 0)
                         [:input.button {:type "submit" :value "Toggle Admin"}])
                       :else
                       (form-to [:post (str "/groups/" group-id)]
                         (hidden-field "username" (:user active))
                         (hidden-field "admin" 1)
                         [:input.button.green-button {:type "submit" :value "Toggle Admin"}]))]
                    [:td 
                     (if (= account (:user active))
                       ""
                       (form-to [:delete (str "/groups/" group-id)]
                         (hidden-field "username" (:user active))
                         [:input.button.red-button {:type "submit" :value "Remove Member"}]))]))])]]
           (unordered-list (map user-link (sort (map :user actives)))))
         (error-list errors)
         (when admin?
           [:div.add-member
            [:h2 "Add member to group"]
            (form-to [:post (str "/groups/" group-id)]
                     [:div
                      [:label
                       "Username "
                       (text-field "username")]]
                     [:div {:class "checkbox"}
                      [:label
                       "Admin? "
                       [:input {:type "checkbox"
                                :name "admin"
                                :id "admin"
                                :value 1
                                :style "width:auto;margin-right:5px"
                                :checked false}]]]
                     (submit-button "Add Member"))])])))
