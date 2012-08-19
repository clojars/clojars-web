(ns clojars.web.jar
  (:require [clojars.web.common :refer [html-doc jar-link group-link
                                        tag jar-url jar-name user-link]]
            [hiccup.core :refer [h]]
            [hiccup.element :refer [link-to]]
            [clojars.maven :refer [jar-to-pom-map]]
            [clojars.db :refer [find-jar jar-exists]]
            [ring.util.codec :refer [url-encode]]
            [clj-stacktrace.repl :refer [pst]])
  (:import java.io.IOException))

(defn url-for [jar]
  (str (jar-url jar) "/versions/" (:version jar)))

(defn maven-jar-url [jar]
 (str "http://search.maven.org/#"
   (url-encode (apply format "artifactdetails|%s|%s|%s|jar"
        ((juxt :group_name :jar_name :version) jar)))))

(defn dependency-link [dep]
  (link-to
    (if (jar-exists (:group_name dep) (:jar_name dep)) (jar-url dep) (maven-jar-url dep))
    (str (jar-name dep) " " (:version dep))))

(defn dependency-section [title id dependencies]
  (if (empty? dependencies) '()
    (list
    [:h3 title]
    [(keyword (str "ul#" id))
     (for [dep dependencies]
       [:li (dependency-link dep)])])))

(defn show-jar [account jar recent-versions count]
  (html-doc account (str (:jar_name jar) " " (:version jar))
            [:h1 (jar-link jar)]
            (:description jar)
            (when-let [homepage (:homepage jar)]
              [:p.homepage
               (try (link-to homepage (str (h homepage)))
                    (catch Exception e
                      ; link-to will throw an exception when a non url
                      ; is given
                      (h homepage)))])

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
             (try
               (let [dependencies (:dependencies (jar-to-pom-map jar))]
                 (concat
                  (dependency-section "dependencies" "dependencies" (remove #(not= (:scope %) "compile") dependencies))))
               (catch IOException e
                 (pst e)
                 [:p.error "Oops. We hit an error opening the metadata POM file for this jar "
                  "so some details are not available."]))
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
