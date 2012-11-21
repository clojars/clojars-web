(ns clojars.web.jar
  (:require [clojars.web.common :refer [html-doc jar-link group-link
                                        tag jar-url jar-name user-link
                                        simple-date]]
            [hiccup.core :refer [h]]
            [hiccup.element :refer [link-to]]
            [hiccup.form :refer [form-to submit-button]]
            [clojars.maven :refer [jar-to-pom-map commit-url]]
            [clojars.auth :refer [authorized?]]
            [clojars.db :refer [find-jar jar-exists]]
            [clojars.promote :refer [blockers]]
            [clojure.set :as set]
            [ring.util.codec :refer [url-encode]]))

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

; handles link-to throwing an exception when given a non-url
(defn safe-link-to [url text]
  (try (link-to url text)
    (catch Exception e text)))

(defn promotion-details [account jar]
  (if (authorized? account (:group_name jar))
    (list [:h3 "promotion"]
          (if (:promoted_at jar)
            [:p (str "Promoted at " (java.util.Date. (:promoted_at jar)))]
            (if-let [issues (seq (blockers (set/rename-keys
                                            jar {:group_name :group
                                                 :jar_name :name})))]
              [:ul#blockers
               (for [i issues]
                 [:li i])]
              (form-to [:post (str "/" (:group_name jar) "/" (:jar_name jar)
                                   "/promote/" (:version jar))]
                       (submit-button "Promote")))))))

(defn show-jar [account jar recent-versions count]
  (html-doc account (str (:jar_name jar) " " (:version jar))
            [:h1 (jar-link jar)]
            (:description jar)
            (when-let [homepage (:homepage jar)]
              [:p.homepage (safe-link-to homepage (h homepage))])
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
             (let [pom (jar-to-pom-map jar)]
               (list
                 [:p "Pushed by " (user-link (:user jar)) " on "
                   [:span {:title (str (java.util.Date. (:created jar)))} (simple-date (:created jar))]
                   (if-let [url (commit-url pom)]
                     [:span.commit-url " with " (link-to url "this commit")])]
                 (promotion-details account jar)
                 (dependency-section "dependencies" "dependencies"
                                     (remove #(not= (:scope %) "compile") (:dependencies pom)))
                 (when-not pom
                   [:p.error "Oops. We hit an error opening the metadata POM file for this jar "
                     "so some details are not available."])))
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
