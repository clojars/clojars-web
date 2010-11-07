(ns clojars.web.group
  (:use clojars.web.common
        clojars.db
        hiccup.core
        hiccup.page-helpers
        hiccup.form-helpers))

(defn show-group [account group members & errors]
  (html-doc account (str group " group")
    [:h1 (str group " group")]
    [:h2 "Jars"]
    (unordered-list (map jar-link (jars-by-group group)))
    [:h2 "Members"]
    (unordered-list (map user-link members))
    (error-list errors)
    (when (some #{account} members)
      [:div {:class :add-member}
       (form-to [:post (str "/groups/" group)]
         (text-field "user")
         (submit-button "add member"))])))
