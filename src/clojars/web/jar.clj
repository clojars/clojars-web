(ns clojars.web.jar
  (:require [clojars.web.common :refer [html-doc jar-link group-link
                                        tag jar-url jar-name user-link]]
            [hiccup.core :refer [h]]
            [hiccup.page-helpers :refer [link-to]]))

(defn url-for [jar]
  (str (jar-url jar) "/versions/" (:version jar)))

(defn show-jar [account jar recent-versions count]
  (html-doc account (:jar_name jar)
            [:h1 (jar-link jar)]
            (:description jar)

            [:div {:class "useit"}
             [:div {:class "lein"}
              [:h3 "leiningen"]
              [:pre
               (tag "[")
               (jar-name jar)
               [:span {:class :string} " \""
                (h (:version jar)) "\""] (tag "]") ]]

             [:div {:class "maven"}
              [:h3 "maven"]
              [:pre
               (tag "<dependency>\n")
               (tag "  <groupId>") (:group_name jar) (tag "</groupId>\n")
               (tag "  <artifactId>") (:jar_name jar) (tag "</artifactId>\n")
               (tag "  <version>") (h (:version jar)) (tag "</version>\n")
               (tag "</dependency>")]]
             [:p "Pushed by " (user-link (:user jar)) " on " (java.util.Date. (:created jar))]
             (when-let [homepage (:homepage jar)]
               [:p (link-to homepage (str (h homepage)))])
              [:h3 "recent versions"]
              [:ul#versions
               (for [v recent-versions]
                 [:li (link-to (url-for (assoc jar
                                          :version (:version v)))
                               (:version v))])]
             [:p (link-to (str (jar-url jar) "/versions")
                          (str "show all versions (" count " total)"))]]))

(defn show-versions [account jar versions]
  (html-doc account (str "all versions of "(jar-name jar))
            [:h1 "all versions of "(jar-name jar)]
            [:div {:class "versions"}
             [:ul
              (for [v versions]
                [:li (link-to (url-for (assoc jar
                                         :version (:version v)))
                              (:version v))])]]))
