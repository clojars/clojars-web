(ns clojars.web.jar
  (:require [clojars.web.common :refer [html-doc jar-link group-link
                                        tag jar-url jar-name user-link
                                        jar-fork? single-fork-notice
                                        simple-date]]
            hiccup.core
            [hiccup.element :refer [link-to]]
            [hiccup.form :refer [submit-button]]
            [clojars.web.safe-hiccup :refer [form-to]]
            [clojars.maven :refer [jar-to-pom-map commit-url]]
            [clojars.auth :refer [authorized?]]
            [clojars.db :refer [find-jar jar-exists]]
            [clojars.promote :refer [blockers]]
            [clojars.stats :as stats]
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

(defn fork-notice [jar]
  (when (jar-fork? jar)
    single-fork-notice))

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
            [:div.grid_9.alpha
             [:h1 (jar-link jar)]]
            [:div.grid_3.omega
             [:small.downloads
              (let [stats (stats/all)]
                [:p
                 "Downloads: "
                 (stats/download-count stats
                                       (:group_name jar)
                                       (:jar_name jar))
                 [:br]
                 "This version: "
                 (stats/download-count stats
                                       (:group_name jar)
                                       (:jar_name jar)
                                       (:version jar))])]]
            [:div.grid_12.alpha.omega
             (:description jar)
             (when-let [homepage (:homepage jar)]
               [:p.homepage (safe-link-to homepage homepage)])
             [:div {:class "useit"}
              [:div {:class "lein"}
               [:h3 "leiningen"]
               [:pre
                (tag "[")
                (jar-name jar)
                [:span {:class :string} " \""
                 (:version jar) "\""] (tag "]") ]]
              
              [:div {:class "gradle"}
               [:h3 "gradle"]
               [:pre
                "compile "
                \"
                (:group_name jar)
                ":"
                (:jar_name jar)
                ":"
                (:version jar)
                \"]]

              [:div {:class "maven"}
               [:h3 "maven"]
               [:pre
                (tag "<dependency>\n")
                (tag "  <groupId>") (:group_name jar) (tag "</groupId>\n")
                (tag "  <artifactId>") (:jar_name jar) (tag "</artifactId>\n")
                (tag "  <version>") (:version jar) (tag "</version>\n")
                (tag "</dependency>")]]
              (let [pom (jar-to-pom-map jar)]
                (list
                 [:p "Pushed by " (user-link (:user jar)) " on "
                  [:span {:title (str (java.util.Date. (:created jar)))} (simple-date (:created jar))]
                  (if-let [url (commit-url pom)]
                    [:span.commit-url " with " (link-to url "this commit")])]
                 (fork-notice jar)
                 (promotion-details account jar)
                 (dependency-section "dependencies" "dependencies"
                                     (remove #(not= (:scope %) "compile") (:dependencies pom)))
                 (when-not pom
                   [:p.error "Oops. We hit an error opening the metadata POM file for this project "
                    "so some details are not available."])))
              [:h3 "recent versions"]
              [:ul#versions
               (for [v recent-versions]
                 [:li (link-to (url-for (assoc jar
                                          :version (:version v)))
                               (:version v))])]
              [:p (link-to (str (jar-url jar) "/versions")
                           (str "show all versions (" count " total)"))]]]))

(defn show-versions [account jar versions]
  (html-doc account (str "all versions of "(jar-name jar))
            [:h1 "all versions of "(jar-link jar)]
            [:div {:class "versions"}
             [:ul
              (for [v versions]
                [:li (link-to (url-for (assoc jar
                                         :version (:version v)))
                              (:version v))])]]))

(defn svg-template [jar-id version]
  (let [width-px (+ 138 (* (+ (count jar-id) (count version)) 6))]
    [:svg {:width (str (* width-px 0.044) "cm")
           :height "0.88cm"
           :viewBox (str "0 0 " width-px " 20")
           :xmlns "http://www.w3.org/2000/svg"
           :version "1.1"}
     [:rect {:x 0, :y 0, :width width-px, :height 20,
             :rx 3, :fill "#444444"}]
     [:rect {:x 2, :y 2, :width (- width-px 4), :height 16,
             :rx 3, :fill "#1f1f1f"}]

     [:text {:x 7, :y 13, :font-family "monospace",
             :font-size 10, :fill "white"}
      [:tspan {:fill "#ffcfaf"} "["]
      [:tspan {:fill "#ffffff"} jar-id]
      [:tspan " "]
      [:tspan {:fill "#cc9393"} (str \" version \")]
      [:tspan {:fill "#ffcfaf"} "]"]]

     [:text {:x (- width-px 90), :y 15, :font-family "Verdana",
             :font-size 7, :fill "white"}
      [:tspan "Powered by "]
      [:tspan {:fill "#ffcfaf"} "clojars.org"]]]))

(defn make-latest-version-svg [group-id artifact-id]
  (let [jar (find-jar group-id artifact-id)]
    (hiccup.core/html
     "<?xml version=\"1.0\" standalone=\"no\"?>"
     "<!DOCTYPE svg PUBLIC \"-//W3C//DTD SVG 1.1//EN\"
 \"http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd\">"
     (svg-template (jar-name jar) (:version jar)))))
