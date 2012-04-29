(ns clojars.web.group
  (:require [clojars.web.common :refer [html-doc jar-link user-link error-list]]
            [clojars.db :refer [jars-by-groupname]]
            [hiccup.page-helpers :refer [unordered-list]]
            [hiccup.form-helpers :refer [form-to text-field submit-button]]))

(defn show-group [account groupname membernames & errors]
  (html-doc account (str groupname " group")
    [:h1 (str groupname " group")]
    [:h2 "Jars"]
    (unordered-list (map jar-link (jars-by-groupname groupname)))
    [:h2 "Members"]
    (unordered-list (map user-link membernames))
    (error-list errors)
    (when (some #{account} membernames)
      [:div {:class :add-member}
       (form-to [:post (str "/groups/" groupname)]
         (text-field "user")
         (submit-button "add member"))])))
