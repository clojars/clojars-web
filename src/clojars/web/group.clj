(ns clojars.web.group
  (:require [clojars.web.common :refer [html-doc jar-link user-link error-list]]
            [clojars.db :refer [jars-by-groupname]]
            [clojars.auth :refer [authorized?]]
            [hiccup.element :refer [unordered-list]]
            [hiccup.form :refer [text-field submit-button]]
            [clojars.web.safe-hiccup :refer [form-to]]))

(defn show-group [db account groupname membernames & errors]
  (html-doc account (str groupname " group")
    [:div.small-section.col-md-6.col-lg-6.col-sm-6.col-xs-12
     [:h1 (str groupname " group")]
     [:h2 "Projects"]
     (unordered-list (map jar-link (jars-by-groupname db groupname)))
     [:h2 "Members"]
     (unordered-list (map user-link (sort membernames)))
     (error-list errors)
     (when (authorized? db account groupname)
       [:div.add-member
        (form-to [:post (str "/groups/" groupname)]
                 (text-field "username")
                 (submit-button "add member"))])]))
