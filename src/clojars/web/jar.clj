(ns clojars.web.jar
  (:require
   [cheshire.core :as json]
   [clojars.auth :as auth]
   [clojars.config :refer [config]]
   [clojars.db :as db :refer [find-jar jar-exists]]
   [clojars.file-utils :as fu]
   [clojars.stats :as stats]
   [clojars.web.common :refer [audit-table html-doc jar-link
                               tag jar-url jar-name jar-versioned-url group-is-name?
                               user-link jar-fork? jar-notice single-fork-notice
                               simple-date verified-group-badge]]
   [clojars.web.helpers :as helpers]
   [clojars.web.structured-data :as structured-data]
   [clojure.set :as set]
   [hiccup.core]
   [hiccup.element :refer [link-to]]
   [ring.util.codec :refer [url-encode]])
  (:import
   (java.net
    URI)))

(defn repo-url [jar]
  (str (:cdn-url (config)) "/" (-> jar :group_name fu/group->path) "/" (:jar_name jar) "/"))

(defn maven-jar-url [jar]
  (str "http://search.maven.org/#"
       (url-encode (apply format "artifactdetails|%s|%s|%s|jar"
                          ((juxt :group_name :jar_name :version) jar)))))

(def ^:private http-url-re #"^https?://")
(def ^:private vcs-type-re #"^https?://(github|gitlab).com/")
(def ^:private vcs-path-re #"^https?://[^/]+/([^/]+)/([^/]+)")

(defn- vcs-host-info [jar]
  (let [url (str (get-in jar [:scm :url]))]
    (when (re-find http-url-re url)
      (let [[_ type] (re-find vcs-type-re url)
            [_ user name] (re-find vcs-path-re url)]
        {:repo-url url
         :type (keyword type)
         :user user
         :repo-name name}))))

(defn- vcs-host-tree-url [jar]
  (let [{:keys [tag]} (:scm jar)
        {:keys [repo-url]} (vcs-host-info jar)]
    (when (and repo-url tag)
      (str repo-url "/tree/" tag))))

(defn dependency-link [db dep]
  (link-to
   (if (jar-exists db (:group_name dep) (:jar_name dep)) (jar-url dep) (maven-jar-url dep))
   (str (jar-name dep) " " (:version dep))))

(defn version-badge-url [jar]
  (format "https://img.shields.io/clojars/v%s.svg" (jar-url jar)))

(defn badge-markdown [jar]
  (format
   "[![Clojars Project](%s)](https://clojars.org%s)"
   (version-badge-url jar)
   (jar-url jar)))

;; handles link-to throwing an exception when given a non-url
(defn safe-link-to [url text]
  (try (link-to url text)
       (catch Exception _ text)))

(defn fork-notice [jar]
  (when (jar-fork? jar)
    (list single-fork-notice)))

(defn breadcrumbs [{:keys [group_name jar_name] :as jar}]
  ;; TODO: this could be made more semantic by attaching the metadata to #jar-title, but we're waiting on https://github.com/clojars/clojars-web/issues/482
  (structured-data/breadcrumbs
   (if (group-is-name? jar)
     [{:url  (str "https://clojars.org/" (jar-name jar))
       :name jar_name}]
     [{:url  (str "https://clojars.org/groups/" group_name)
       :name group_name}
       ;; TODO: Not sure if this is a dirty hack or a stroke of brilliance
      {:url  (str "https://clojars.org/" (jar-name jar))
       :name jar_name}])))

(defn- vcs-logo [type]
  (apply helpers/retinized-image
   (case type
     :github ["/images/github-mark.png" "GitHub"]
     :gitlab ["/images/gitlab-mark-black.png" "GitLab"]
     ["/images/git-mark.png" "VCS"])))

(defn- vcs-host-link [jar]
  (if-let [{:keys [repo-url type user repo-name]} (vcs-host-info jar)]
    (link-to repo-url
             (vcs-logo type)
             (format "%s/%s" user repo-name))
    [:p (vcs-logo :unknown) "N/A"]))

(defn cljdoc-uri
  "Returns the URI that this JAR would have on cljdoc.org. Doesn't validate that
  there is actually a currently published version on cljdoc.org."
  [jar]
  (URI.
   "https"
   "cljdoc.org"
   (format "/d/%s/%s/%s"
           (:group_name jar)
           (:jar_name jar)
           (:version jar))
   nil))

(defn cljdoc-link [jar]
  (link-to (cljdoc-uri jar)
           [:img {:src "/images/cljdoc-icon.svg" :alt "cljdoc documentation" :height "16"}]
           "cljdoc"))

(defn leiningen-coordinates [jar]
  (list
   [:h2 "Leiningen/Boot"]
   [:div#leiningen-coordinates.package-config-example
    {:onClick "selectText('leiningen-coordinates');"}
    [:pre
     (tag "[")
     (jar-name jar)
     [:span.string " \""
      (:version jar) "\""] (tag "]")]]))

(defn clojure-cli-coordinates [{:keys [group_name jar_name version]}]
  (list
   [:h2 "Clojure CLI/deps.edn"]
   [:div#deps-coordinates.package-config-example
    {:onClick "selectText('deps-coordinates');"}
    [:pre
     (str group_name "/" jar_name)
     \space
     (tag "{")
     ":mvn/version "
     [:span.string \" version \"]
     (tag "}")]]))

(defn gradle-coordinates [{:keys [group_name jar_name version]}]
  (list
   [:h2 "Gradle"]
   [:div#gradle-coordinates.package-config-example
    {:onClick "selectText('gradle-coordinates');"}
    [:pre
     "implementation("
     [:span.string \" group_name ":" jar_name ":" version \"]
     \)]]))

(defn maven-coordinates [{:keys [group_name jar_name version]}]
  (list
   [:h2 "Maven"]
   [:div#maven-coordinates.package-config-example
    {:onClick "selectText('maven-coordinates');"}
    [:pre
     (tag "<dependency>\n")
     (tag "  <groupId>") group_name (tag "</groupId>\n")
     (tag "  <artifactId>") jar_name (tag "</artifactId>\n")
     (tag "  <version>") version (tag "</version>\n")
     (tag "</dependency>")]]))

(defn coordinates [jar]
  (for [f [leiningen-coordinates
           clojure-cli-coordinates
           gradle-coordinates
           maven-coordinates]]
    (f jar)))

(defn pushed-by [jar]
  (list
   [:h4 "Pushed by"]
   (user-link (:user jar)) " on "
   [:span {:title (str (:created jar))} (simple-date (:created jar))]
   (when-let [url (vcs-host-tree-url jar)]
     [:span.commit-url " with " (link-to url "this git tree")])))

(defn versions [jar recent-versions count]
  (list
   [:h4 "Recent Versions"]
   [:ul#versions
    (for [v recent-versions]
      [:li (link-to (jar-versioned-url (assoc jar
                                              :version (:version v)))
                    (:version v))])]
    ;; by default, 5 versions are shown. If there are only 5 to
    ;; see, then there's no reason to show the 'all versions' link
   (when (> count 5)
     [:p (link-to (str (jar-url jar) "/versions")
                  (str "Show All Versions (" count " total)"))])))

(defn dependency-section [db title id dependencies]
  (when (seq dependencies)
    (list
     [:h3 title]
     [(keyword (str "ul#" id))
      (for [dep dependencies]
        [:li (dependency-link db dep)])])))

(defn dependencies [db {:keys [group_name jar_name version]}]
  (dependency-section db "Dependencies" "dependencies"
                      (remove #(not= (:scope %) "compile")
                              (map
                               #(set/rename-keys % {:dep_group_name :group_name
                                                    :dep_jar_name   :jar_name
                                                    :dep_version    :version
                                                    :dep_scope      :scope})
                               (db/find-dependencies db
                                                     group_name jar_name version)))))

(defn homepage [{:keys [homepage]}]
  (when homepage
    (list
     [:h4 "Homepage"]
     (safe-link-to homepage homepage))))

(defn licenses [jar]
  (when-let [licenses (seq (:licenses jar))]
    (list
     [:h4 "License"]
     [:ul#licenses
      (for [{:keys [name url]} licenses]
        [:li (safe-link-to url name)])])))

(defn version-badge [jar]
  (list
   [:h4 "Version Badge"]
   [:p
    "Want to display the "
    (link-to (version-badge-url jar) "latest version")
    " of your project on GitHub? Use the markdown code below!"]
   [:textarea#version-badge
    {:readonly "readonly" :rows 6 :onClick "selectText('version-badge')"}
    (badge-markdown jar)]))

(defn show-jar [db stats account
                {:keys [group_name jar_name verified-group? version] :as jar}
                recent-versions count]
  (let [total-downloads        (-> (stats/download-count stats
                                                         group_name jar_name)
                                   (stats/format-stats))
        downloads-this-version (-> (stats/download-count stats
                                                         group_name jar_name version)
                                   (stats/format-stats))
        title                  (format "[%s/%s \"%s\"]"
                                       group_name jar_name version)]
    (html-doc
     title
     {:account     account
      :description (format "%s %s" title (:description jar))
      :label1      (str "Total downloads / this version")
      :data1       (format "%s / %s" total-downloads downloads-this-version)
      :label2      "Coordinates"
      :data2       (format "[%s \"%s\"]" (jar-name jar) version)}
     [:div.light-article.row
      (breadcrumbs jar)
      (helpers/select-text-script)
      [:div#jar-title.col-xs-12.col-sm-9
       [:div
        [:h1 (jar-link jar)]
        (when verified-group?
          verified-group-badge)]
       [:p.description (:description jar)]
       [:ul#jar-info-bar.row
        [:li.col-xs-12.col-sm-3 (vcs-host-link jar)]
        [:li.col-xs-12.col-sm-3 (cljdoc-link jar)]
        [:li.col-xs-12.col-sm-3
         total-downloads
         " Downloads"]
        [:li.col-xs-12.col-sm-3
         downloads-this-version
         " This Version"]]
       (jar-notice group_name jar_name)
       (coordinates jar)
       (fork-notice jar)
       (when (auth/authorized-group-access? db account group_name)
         (audit-table db (format "%s/%s %s" group_name jar_name version)
                      {:group-name group_name
                       :jar-name jar_name
                       :version version}))]
      [:ul#jar-sidebar.col-xs-12.col-sm-3
       [:li (pushed-by jar)]
       [:li (versions jar recent-versions count)]
       (when-let [dependencies (dependencies db jar)]
         [:li dependencies])
       (when-let [homepage (homepage jar)]
         [:li.homepage homepage])
       (when-let [licenses (licenses jar)]
         [:li.license licenses])
       [:li (version-badge jar)]]])))

(defn repo-note [jar]
  [:div
   [:h2 "Maven Repository"]
   [:p
    "If you are looking for URLs to jar files or "
    (link-to "https://github.com/clojars/clojars-web/wiki/Stable-SNAPSHOT-Identifiers" "stable identifiers")
    " for SNAPSHOT versions you can take a look at "
    (link-to (repo-url jar) (format "the full Maven repository for %s." (jar-name jar)))]])

(defn show-versions [account jar versions]
  (html-doc (str "all versions of " (jar-name jar)) {:account account}
            [:div.light-article
             [:h1 "all versions of " (jar-link jar)]
             [:div.versions
              [:ul
               (for [v versions]
                 [:li.col-xs-12.col-sm-6.col-md-4.col-lg-3
                  (link-to (jar-versioned-url (assoc jar :version (:version v)))
                           (:version v))])]]]
            [:div.light-article
             (repo-note jar)]))

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

(defn make-latest-version-json
  "Return the latest version of a JAR as JSON"
  [db group-id artifact-id]
  (let [jar (find-jar db group-id artifact-id)]
    (json/generate-string (select-keys jar [:version]))))
