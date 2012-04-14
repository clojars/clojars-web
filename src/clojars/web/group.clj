(ns clojars.web.group
  (:use clojars.web.common
        clojars.db
        hiccup.core
        hiccup.page-helpers
        hiccup.form-helpers))

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
