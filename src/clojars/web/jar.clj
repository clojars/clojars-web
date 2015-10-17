(ns clojars.web.jar
  (:require [clojars.web.common :refer [html-doc jar-link group-link
                                        tag jar-url jar-name user-link
                                        jar-fork? single-fork-notice
                                        simple-date]]
            hiccup.core
            [hiccup.element :refer [link-to image]]
            [hiccup.form :refer [submit-button]]
            [clojars.web.safe-hiccup :refer [form-to]]
            [clojars.maven :refer [jar-to-pom-map commit-url github-info]]
            [clojars.auth :refer [authorized?]]
            [clojars.db :refer [find-jar jar-exists]]
            [clojars.promote :refer [blockers]]
            [clojars.stats :as stats]
            [clojure.set :as set]
            [ring.util.codec :refer [url-encode]]
            [cheshire.core :as json]))

(defn url-for [jar]
  (str (jar-url jar) "/versions/" (:version jar)))

(defn maven-jar-url [jar]
 (str "http://search.maven.org/#"
   (url-encode (apply format "artifactdetails|%s|%s|%s|jar"
        ((juxt :group_name :jar_name :version) jar)))))

(defn dependency-link [db dep]
  (link-to
    (if (jar-exists db (:group_name dep) (:jar_name dep)) (jar-url dep) (maven-jar-url dep))
    (str (jar-name dep) " " (:version dep))))

(defn version-badge-url [jar]
  (str (jar-url jar) "/latest-version.svg"))

(defn badge-markdown [jar]
  (str "[![Clojars Project]"
       "(http://clojars.org"
       (jar-url jar)
       "/latest-version.svg)]"
       "(http://clojars.org"
       (jar-url jar)
       ")"))

(defn dependency-section [db title id dependencies]
  (if (empty? dependencies) '()
    (list
    [:h3 title]
    [(keyword (str "ul#" id))
     (for [dep dependencies]
       [:li (dependency-link db dep)])])))

; handles link-to throwing an exception when given a non-url
(defn safe-link-to [url text]
  (try (link-to url text)
    (catch Exception e text)))

(defn fork-notice [jar]
  (when (jar-fork? jar)
    single-fork-notice))

(defn promoted? [jar]
  (:promoted_at jar))

(defn promoted-at [jar]
  [:p (str "Promoted at " (java.util.Date. (:promoted_at jar)))])

(defn promotion-issues [db jar]
  (seq (blockers db (set/rename-keys jar {:group_name :group
                                          :jar_name :name}))))

(defn promotion-details [db account jar]
  (when (authorized? db account (:group_name jar))
    (list [:h2 "Promotion"]
          (if (promoted? jar)
            (promoted-at jar)
            (if-let [issues (promotion-issues db jar)]
              (list [:h3 "Issues Blocking Promotion"]
                    [:ul#blockers
                     (for [i issues]
                       [:li i])])
              (form-to [:post (str "/" (:group_name jar) "/" (:jar_name jar)
                                   "/promote/" (:version jar))]
                       (submit-button "Promote")))))))

(defn show-jar [db account jar recent-versions count]
  (html-doc account (str (:jar_name jar) " " (:version jar))
            (let [pom-map (jar-to-pom-map jar)]
              [:div.light-article.row
               [:div#jar-title.col-sm-9.col-lg-9.col-xs-12.col-md-9
                [:h1 (jar-link jar)]
                [:p.description (:description jar)]
                (let [stats (stats/all)]
                  [:ul#jar-info-bar.row
                   [:li.col-md-4.col-sm-4.col-xs-12.col-lg-4
                    (if-let [gh-info (github-info pom-map)]
                      (link-to {:target "_blank"}
                               (format "https://github.com/%s" gh-info)
                               (image "/images/GitHub-Mark-16px.png" "GitHub")
                               gh-info)
                      [:p.github
                       (image "/images/GitHub-Mark-16px.png" "GitHub")
                       "N/A"])]
                   [:li.col-md-4.col-sm-4.col-xs-12.col-lg-4
                    (stats/download-count stats
                                              (:group_name jar)
                                              (:jar_name jar))
                    " Downloads"]
                   [:li.col-md-4.col-sm-4.col-xs-12.col-lg-4
                    (stats/download-count stats
                                              (:group_name jar)
                                              (:jar_name jar)
                                              (:version jar))
                    " This Version"]])
                (when-not pom-map
                  [:p.error "Oops. We hit an error opening the metadata POM file for this project "
                   "so some details are not available."])
                [:h2 "Leiningen"]
                [:div.package-config-example
                 [:pre
                  (tag "[")
                  (jar-name jar)
                  [:span.string " \""
                   (:version jar) "\""] (tag "]") ]]

                [:h2 "Gradle"]
                [:div.package-config-example
                 [:pre
                  "compile "
                  [:span.string
                   \"
                   (:group_name jar)
                   ":"
                   (:jar_name jar)
                   ":"
                   (:version jar)
                   \"]]]

                [:h2 "Maven"]
                [:div.package-config-example
                 [:pre
                  (tag "<dependency>\n")
                  (tag "  <groupId>") (:group_name jar) (tag "</groupId>\n")
                  (tag "  <artifactId>") (:jar_name jar) (tag "</artifactId>\n")
                  (tag "  <version>") (:version jar) (tag "</version>\n")
                  (tag "</dependency>")]]
                (list
                 (fork-notice jar)
                 (promotion-details db account jar))]
               [:ul#jar-sidebar.col-sm-3.col-xs-12.col-md-3.col-lg-3
                [:li
                 [:h4 "Pushed by"]
                 (user-link (:user jar)) " on "
                 [:span {:title (str (java.util.Date. (:created jar)))} (simple-date (:created jar))]
                 (if-let [url (commit-url pom-map)]
                   [:span.commit-url " with " (link-to url "this commit")])]
                [:li
                 [:h4 "Recent Versions"]
                 [:ul#versions
                  (for [v recent-versions]
                    [:li (link-to (url-for (assoc jar
                                             :version (:version v)))
                                  (:version v))])]
                 ;; by default, 5 versions are shown. If there are only 5 to
                 ;; see, then there's no reason to show the 'all versions' link
                 (when (> count 5)
                   [:p (link-to (str (jar-url jar) "/versions")
                                (str "Show All Versions (" count " total)"))])]
                (let [dependencies
                      (dependency-section db "Dependencies" "dependencies"
                                          (remove #(not= (:scope %) "compile") (:dependencies pom-map)))]
                  (when-not (empty? dependencies)
                    [:li dependencies]))
                (when-let [homepage (:homepage jar)]
                  [:li.homepage
                   [:h4 "Homepage"]
                   (safe-link-to homepage homepage)])
                [:li
                 [:h4 "Version Badge"]
                 [:p
                  "Want to display the "
                  (link-to (version-badge-url jar) "latest version")
                  " of your project on Github? Use the markdown code below!"]
                 [:textarea {:readonly "readonly" :rows 4} (badge-markdown jar)]
                 ]
                ]])))

(defn show-versions [account jar versions]
  (html-doc account (str "all versions of "(jar-name jar))
            [:div.light-article
             [:h1 "all versions of "(jar-link jar)]
             [:div.versions
              [:ul
               (for [v versions]
                 [:li.col-md-4.col-lg-3.col-sm-6.col-xs-12
                  (link-to (url-for (assoc jar
                                      :version (:version v)))
                           (:version v))])]]]))

(let [border-color "#e2e4e3"
      bg-color "#fff"
      artifact-color  "#4098cf"
      version-color "#87cf29"
      bracket-color "#ffb338"
      ampersand-color "#888"
      clojars-color "#ffb338"]
  (defn svg-template [jar-id version]
    (let [width-px (+ 138 (* (+ (count jar-id) (count version)) 6))]
      [:svg {:width (str (* width-px 0.044) "cm")
             :height "0.90cm"
             :viewBox (str "0 0 " width-px " 20")
             :xmlns "http://www.w3.org/2000/svg"
             :version "1.1"}
       [:rect {:x 0,
               :y 0,
               :width width-px,
               :height 20,
               :rx 3,
               :fill border-color}]
       [:rect {:x 2,
               :y 2,
               :width (- width-px 4),
               :height 16,
               :rx 3,
               :fill bg-color}]
       [:text {:x 7,
               :y 13,
               :font-family "monospace",
               :font-size 10,
               :fill "#dddddd"}
        [:tspan {:fill bracket-color} "["]
        [:tspan {:fill artifact-color} jar-id]
        [:tspan " "]
        [:tspan {:fill version-color} (str \" version \")]
        [:tspan {:fill bracket-color} "]"]]
       [:text {:x (- width-px 55),
               :y 14,
               :font-family "Verdana",
               :font-size 8,
               :fill ampersand-color}
        [:tspan "@"]
        [:tspan {:fill clojars-color} "clojars.org"]]])))

(defn make-latest-version-svg [db group-id artifact-id]
  (let [jar (find-jar db group-id artifact-id)]
    (hiccup.core/html
     "<?xml version=\"1.0\" standalone=\"no\"?>"
     "<!DOCTYPE svg PUBLIC \"-//W3C//DTD SVG 1.1//EN\"
 \"http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd\">"
     (svg-template (jar-name jar) (:version jar)))))

(defn make-latest-version-json [db group-id artifact-id]
  "Return the latest version of a JAR as JSON"
  (let [jar (find-jar db group-id artifact-id)]
    (json/generate-string (select-keys jar [:version]))))
