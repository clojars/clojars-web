(ns clojars.web.jar
  (:use clojars.web.common
        compojure))

(defn show-jar [account jar]
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
               [:p (link-to homepage (str (h homepage)))])]))
