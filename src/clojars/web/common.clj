(ns clojars.web.common
  (:require
   [clojars.maven :as maven]
   [clojars.web.helpers :as helpers]
   [clojars.web.safe-hiccup :refer [form-to html5 raw]]
   [clojars.web.structured-data :as structured-data]
   [clojars.db :as db]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [hiccup.core :refer [html]]
   [hiccup.element :refer [link-to unordered-list image]]
   [hiccup.page :refer [include-css include-js]]
   [ring.util.codec :refer [url-encode]])
  (:import
   (java.text SimpleDateFormat)))

(defn when-ie [& contents]
  (str
   "<!--[if lt IE 9]>"
   (html contents)
   "<![endif]-->"))

(defn footer
  "We normally want to include links in the footer, except on pages which contain sensitive URL's like password reset.
  This is so users don't accidentally leak the password reset URL in the referer header."
  [footer-links?]
  (if footer-links?
    [:footer.row
     (link-to "https://github.com/clojars/clojars-web/wiki/About" "about")
     (link-to "https://clojars.statuspage.io" "status")
     (link-to "/projects" "projects")
     (link-to "https://github.com/clojars/clojars-web/wiki/Contributing" "contribute")
     (link-to "https://groups.google.com/forum/?fromgroups#!topicsearchin/clojars-maintainers/group:clojars-maintainers$20AND$20subject:ann" "news")
     (link-to "https://github.com/clojars/clojars-web/wiki/Contact" "contact")
     (link-to "https://github.com/clojars/clojars-web" "code")
     (link-to "/security" "security")
     (link-to "/dmca" "DMCA")
     (link-to "https://github.com/clojars/clojars-web/wiki/" "help")
     (link-to "https://github.com/clojars/clojars-web/wiki/Data" "API")
     [:div.sponsors
      [:div.sponsors-title
       "sponsored by"
       (link-to "https://www.bountysource.com/teams/clojars/backers" "individual contributors")
       "with hosting costs covered by:"]
      [:div.sponsors-group
       [:div.sponsor
        (link-to "https://shortcut.com/"
                 (image "/images/shortcut-logo.png" "Shortcut Software Company"))]]
      [:div.sponsors-title
       "and in-kind sponsorship from:"]
      [:div.sponsors-group
       [:div.sponsor
        (link-to "https://www.deps.co"
                 (image "/images/deps-logo.png" "Deps"))]
       [:div.sponsor
        (link-to "https://dnsimple.link/resolving-clojars"
                 [:span "resolving with" [:br]]
                 [:span
                  (image "https://cdn.dnsimple.com/assets/resolving-with-us/logo-light.png" "DNSimple")])]
       [:div.sponsor
        (link-to "http://fastly.com/"
                 (image "/images/fastly-logo.png" "Fastly"))]]
      [:div.sponsors-group
       [:div.sponsor
        (link-to "https://fastmail.com/"
                 (image "/images/fastmail-logo.png" "Fastmail"))]
       [:div.sponsor
        (link-to "https://pingometer.com/"
                 (image "/images/pingometer-logo.svg" "Pingometer"))]
       [:div.sponsor
        (link-to "http://sentry.io/"
                 (image "/images/sentry-logo.png" "Sentry"))]
       [:div.sponsor
        (link-to "https://www.statuspage.io"
                 (image "/images/statuspage-io-logo.svg" "StatusPage.io"))]]]
     [:div.sponsors
      [:div.sponsors-group
       [:div.sponsor
        [:span "remixed by" [:br]]
        [:span (link-to "http://www.bendyworks.com/"
                        [:img {:src "/images/bendyworks-logo.svg" :alt "Bendyworks Inc." :width "150"}])]]
       [:div.sponsor
        [:span "member project of" [:br]]
        [:span (link-to "https://clojuriststogether.org/"
                        [:img {:src "/images/clojurists-together-logo.png" :alt "Clojurists Together Foundation" :height "40"}])]]]]]
    [:footer.row]))

(defn html-doc [title ctx & body]
  (html5 {:lang "en"}
         [:head
          [:meta {:charset "utf-8"}]
          [:meta {:name "viewport" :content "width=device-width,initial-scale=1"}]
          [:link {:type "application/opensearchdescription+xml"
                  :title "Clojars"
                  :href "/opensearch.xml"
                  :rel "search"}]
          [:link {:href "/favicon.ico"
                  :rel "shortcut icon"}]
          (structured-data/meta-tags (assoc ctx :title (if title
                                                         title
                                                         "Clojars"))) ;; TODO: talk about whether we should refactor signature of html-doc
          [:title
           (when title
             (str title " - "))
           "Clojars"]
          (map #(include-css (str "/stylesheets/" %))
         ;; Bootstrap was customized to only include the 'grid' styles
         ;; (then the default colors were removed)
         ;; more info: http://getbootstrap.com/css/#grid
               ["reset.css" "vendor/bootstrap/bootstrap.css" "screen.css"])
          (raw (when-ie (include-js "/js/html5.js")))
          (include-js "/js/jquery-3.6.0.min.js")
          (include-js "/js/selectText.js")
          (for [path (:extra-js ctx)]
            (include-js path))]
         [:body.container-fluid
          [:div#content-wrapper
           [:header.small-header.row
            [:div.home.col-xs-6.col-sm-3
             (link-to "/" (helpers/retinized-image "/images/clojars-logo-tiny.png" "Clojars"))
             [:h1 (link-to "/" "Clojars")]]
            [:div.col-xs-6.col-sm-3
             [:form {:action "/search"}
              [:input {:type "search"
                       :name "q"
                       :id "search"
                       :class "search"
                       :placeholder "Search projects..."
                       :value (:query ctx)
                       :required true}]]]
            [:nav.navigation.main-navigation.col-xs-12.col-sm-6
             (if (:account ctx)
               (unordered-list
                [(link-to "/" "dashboard")
                 (link-to "/logout" "logout")])
               (unordered-list
                [(link-to "/login" "login")
                 (link-to "/register" "register")]))]]
           (when (:account ctx)
             [:div.row
              [:nav.navigation.secondary-navigation.col-xs-24.col-sm-12
               (unordered-list
                [(link-to "/notification-preferences" "notification prefs")
                 (link-to "/mfa" "two-factor auth")
                 (link-to "/profile" "profile")
                 (link-to "/tokens" "deploy tokens")])]])
           body
           (footer (get ctx :footer-links? true))]]))

(defn html-doc-with-large-header [title ctx & body]
  (html5 {:lang "en"}
         [:head
          [:meta {:charset "utf-8"}]
          [:link {:type "application/opensearchdescription+xml"
                  :title "Clojars"
                  :href "/opensearch.xml"
                  :rel "search"}]

          [:link {:rel "apple-touch-icon" :sizes "57x57" :href "/apple-touch-icon-57x57.png?v=47K2kprJd7"}]
          [:link {:rel "apple-touch-icon" :sizes "60x60" :href "/apple-touch-icon-60x60.png?v=47K2kprJd7"}]
          [:link {:rel "apple-touch-icon" :sizes "72x72" :href "/apple-touch-icon-72x72.png?v=47K2kprJd7"}]
          [:link {:rel "apple-touch-icon" :sizes "76x76" :href "/apple-touch-icon-76x76.png?v=47K2kprJd7"}]
          [:link {:rel "apple-touch-icon" :sizes "114x114" :href "/apple-touch-icon-114x114.png?v=47K2kprJd7"}]
          [:link {:rel "apple-touch-icon" :sizes "120x120" :href "/apple-touch-icon-120x120.png?v=47K2kprJd7"}]
          [:link {:rel "apple-touch-icon" :sizes "144x144" :href "/apple-touch-icon-144x144.png?v=47K2kprJd7"}]
          [:link {:rel "apple-touch-icon" :sizes "152x152" :href "/apple-touch-icon-152x152.png?v=47K2kprJd7"}]
          [:link {:rel "icon" :type "image/png" :href "/favicon-32x32.png?v=47K2kprJd7" :sizes "32x32"}]
          [:link {:rel "icon" :type "image/png" :href "/favicon-96x96.png?v=47K2kprJd7" :sizes "96x96"}]
          [:link {:rel "icon" :type "image/png" :href "/favicon-16x16.png?v=47K2kprJd7" :sizes "16x16"}]
          [:link {:rel "manifest" :href "/manifest.json?v=47K2kprJd7"}]
          [:link {:rel "shortcut icon" :href "/favicon.ico?v=47K2kprJd7"}]
          [:meta {:name "apple-mobile-web-app-title" :content "Clojars"}]
          [:meta {:name "application-name" :content "Clojars"}]
          [:meta {:name "msapplication-TileColor" :content "#da532c"}]
          [:meta {:name "msapplication-TileImage" :content "/mstile-144x144.png?v=47K2kprJd7"}]
          [:meta {:name "theme-color" :content "#ffffff"}]

          [:meta {:name "viewport" :content "width=device-width,initial-scale=1"}]
          (structured-data/meta-tags (assoc ctx :title (if title
                                                         title
                                                         "Clojars")))
          [:title
           (when title
             (str title " - "))
           "Clojars"]
          (map #(include-css (str "/stylesheets/" %))
         ;; Bootstrap was customized to only include the 'grid' styles
         ;; (then the default colors were removed)
         ;; more info: http://getbootstrap.com/css/#grid
               ["reset.css" "vendor/bootstrap/bootstrap.css" "screen.css"])
          (raw (when-ie (include-js "/js/html5.js")))]
         [:body.container-fluid
          [:div.hero.row
           [:header
            [:div.home.col-xs-12.col-sm-6
             (link-to "/" (helpers/retinized-image "/images/clojars-logo.png" "Clojars"))
             [:h1
              (link-to "/" "Clojars")]]
            [:nav.navigation.main-navigation.col-xs-12.col-sm-6
             (if (:account ctx)
               (unordered-list
                [(link-to "/" "dashboard")
                 (link-to "/profile" "profile")
                 (link-to "/logout" "logout")])
               (unordered-list
                [(link-to "/login" "login")
                 (link-to "/register" "register")]))]
            [:h2.hero-text.row
             [:span.col-md-12
              [:span.heavy "Clojars"]
              " is an "
              [:span.heavy "easy to use"]
              " community repository for "]
             [:span.col-md-12
              " open source Clojure libraries."]]]
           [:div.search-form-container.col-xs-12
            [:form {:action "/search"}
             [:input {:type "search"
                      :name "q"
                      :id "search"
                      :placeholder "Search projects..."
                      :value (:query ctx)
                      :autofocus true
                      :required true}]
             [:input {:id "search-button"
                      :value "Search"
                      :type "submit"}]]]
           [:h2.getting-started.row
            [:span.col-md-12
             "To get started pushing your own project "
             (link-to "/register" "register")
             " and then"]
            [:span.col-md-12
             " check out the "
             (link-to "https://github.com/clojars/clojars-web/wiki/Tutorial" "tutorial")
             ". Alternatively, "
             (link-to "/projects" "browse the repository")
             "."]]]
          body
          (footer (get ctx :footer-links? true))]))

(defn flash [& msg]
  (when (some some? msg)
    (into [:div#notice.info] msg)))

(defn error-list [errors]
  (when errors
    [:div#notice.error
     [:strong "Blistering barnacles!"]
     "  Something's not shipshape:"
     (unordered-list errors)]))

(defn tag [s]
  (raw (html [:span.tag s])))

(defn jar-url [jar]
  (if (= (:group_name jar) (:jar_name jar))
    (str "/" (:jar_name jar))
    (str "/" (:group_name jar) "/" (:jar_name jar))))

(defn jar-versioned-url [jar]
  (str (jar-url jar) "/versions/" (:version jar)))

(defn group-is-name?
  "Is the group of the artifact the same as its name?"
  [jar]
  (= (:group_name jar) (:jar_name jar)))

(defn jar-name [jar]
  (if (group-is-name? jar)
    (:jar_name jar)
    (str (:group_name jar) "/" (:jar_name jar))))

(defn jar-fork? [jar]
  (re-find #"^org.clojars." (or
                             (:group_name jar)
                             (:group-id jar)
                             "")))

(def single-fork-notice
  [:p.fork-notice.hint
   "Note: this artifact is a non-canonical fork. See "
   (link-to "https://github.com/clojars/clojars-web/wiki/Groups" "the wiki")
   " for more details."])

(def collection-fork-notice
  [:p.fork-notice.hint
   "Note: artifacts in italics are non-canonical forks. See "
   (link-to "https://github.com/clojars/clojars-web/wiki/Groups" "the wiki")
   " for more details."])

(defn maven-search-link
  ([group-id artifact-id]
   (cond->> ""
     group-id    (str (format "g:\"%s\" " group-id))
     artifact-id (str (format "a:\"%s\" " artifact-id))
     true        (str "|ga|1|")
     true        (url-encode)
     true        (str "http://search.maven.org/#search"))))

(defn shadow-notice [group-id artifact-id]
  [:div#notice.info
   "This artifact may shadow a release on Maven Central. You should "
   (link-to (maven-search-link group-id artifact-id)
            "search there")
   " for a canonical release."])

(def jar-notices
  (delay (-> "jar-notices.edn" io/resource slurp edn/read-string)))

(defn jar-notice-for [group-id artifact-id]
  (@jar-notices (symbol (format "%s/%s" group-id artifact-id))))

(defn jar-notice [group-id artifact-id]
  (if-let [notice (jar-notice-for group-id artifact-id)]
    [:div#notice.info (raw notice)]
    (when (and
           (not (maven/can-shadow-maven? group-id artifact-id))
           (maven/exists-on-central? group-id artifact-id))
      (shadow-notice group-id artifact-id))))

(defn jar-link [jar]
  (link-to {:class (when (jar-fork? jar) "fork")}
           (jar-url jar)
           (jar-name jar)))

(defn user-link [username]
  (link-to (str "/users/" username) username))

(defn group-link [groupname]
  (link-to (str "/groups/" groupname) groupname))

(defn format-date [d]
  (when d
    (.format (SimpleDateFormat. "yyyy-MM-dd") d)))

(defn simple-date [d]
  (when d
    (.format (SimpleDateFormat. "MMM d, yyyy") d)))

(defn format-timestamp [t]
  (when t
    (.format (SimpleDateFormat. "MMM d, yyyy HH:mm:ss Z") t)))

(defn page-nav [current-page total-pages & {:keys [base-path] :or {base-path "/projects?page="}}]
  (let [previous-text (raw "&#8592; Previous")
        next-text (raw "Next &#8594;")
        page-range 3
        page-url base-path
        current-page (-> current-page (max 1) (min total-pages))

        main-div [:div.page-nav]
        previous-page (if (= current-page 1)
                        [[:span.previous-page.disabled previous-text]]
                        [[:a.previous-page
                          {:href (str page-url (- current-page 1))}
                          previous-text]])
        before-current (map
                        #(link-to (str page-url %) %)
                        (drop-while
                         #(< % 1)
                         (range (- current-page page-range) current-page)))
        current [[:em.current (str current-page)]]
        after-current (map
                       #(link-to (str page-url %) %)
                       (take-while
                        #(<= % total-pages)
                        (range (inc current-page) (+ current-page 1 page-range))))
        next-page (if (= current-page total-pages)
                    [[:span.next-page.disabled next-text]]
                    [[:a.next-page
                      {:href (str page-url (+ current-page 1))}
                      next-text]])]
    (vec
     (concat main-div previous-page before-current current after-current next-page))))

(defn page-description [current-page per-page total]
  (let [total-pages (-> (/ total per-page) Math/ceil .intValue)
        current-page (-> current-page (max 1) (min total-pages))
        upper (* per-page current-page)]
    [:div {:class "page-description"}
     "Displaying projects "
     [:b (str (-> upper (- per-page) inc) " - " (min upper total))]
     " of "
     [:b total]]))

(defn xml-escape [s]
  (str/escape s {\' "&apos;" \& "&amp;" \< "&lt;" \> "&gt;"}))

(def verified-group-badge
  [:span.verified-group
   (link-to "https://github.com/clojars/clojars-web/wiki/Verified-Group-Names" "verified group")])

(def verified-group-badge-small
  [:span.verified-group.small
   (link-to "https://github.com/clojars/clojars-web/wiki/Verified-Group-Names" "verified")])

(defn- linkify
  [s]
  (when s
    (raw (str/replace s #"(https://[^ )]+)" "<a href='$1'>$1</a>"))))

;; handles link-to throwing an exception when given a non-url
(defn safe-link-to [url text]
  (try (link-to url text)
       (catch Exception _ text)))

(defn- link-project
  [{:as audit :keys [group_name jar_name version]}]
  (when audit
    (cond
      version (safe-link-to (jar-versioned-url audit) (format "%s/%s %s" group_name jar_name version))
      jar_name (jar-link audit)
      :else (group-link group_name))))

(defn audit-table
  [db subject lookup]
  [:div
   [:h2 (format "Audit Log for %s" subject)]
    [:table.audit
     [:tr
      [:th "Tag"]
      [:th "User"]
      [:th "Artifact"]
      [:th "Message"]
      [:th "Timestamp"]]
     (for [audit (db/find-audit db lookup)]
       [:tr
        [:td [:pre (:tag audit)]]
        [:td (when (:user audit) (user-link (:user audit)))]
        [:td (link-project audit)]
        [:td (linkify (:message audit))]
        [:td (:created audit)]])]])

(defn form-table
  [method-action label-input-pairs extra-inputs]
  (form-to
   method-action
   [:div.form-table
    [:table.form-table
     (for [[label input] label-input-pairs]
       [:tr [:td label] [:td input]])]
    extra-inputs]))
