(ns clojars.web.common
  (:require [hiccup.core :refer [html h]]
            [hiccup.page :refer [html5 include-css include-js]]
            [hiccup.element :refer [link-to unordered-list]]
            [hiccup.form :refer [form-to]]))

(defn when-ie [& contents]
  (str
   "<!--[if IE]>"
   (html contents)
   "<![endif]-->"))

(defn html-doc [account title & body]
  (html5   
   [:head
    [:link {:type "application/opensearchdescription+xml" 
            :href "/opensearch.xml"
            :rel "search"}] 
    [:meta {:charset "utf-8"}]
    [:title
     (when title
       (str title " - "))
     "Clojars"]
    (map #(include-css (str "/stylesheets/" %))
         ["reset.css" "grid.css" "screen.css"])
    (when-ie (include-js "/js/html5.js"))]
   [:body
    [:div {:class "container_12 header"}
     [:header
      [:hgroup {:class :grid_4}
       [:h1 (link-to "/" "Clojars")]
       [:h2 "Simple Clojure jar repository"]]
      [:nav
       (if account
         (unordered-list
          [(link-to "/" "dashboard")
           (link-to "/profile" "profile")
           (link-to "/logout" "logout")])
         (unordered-list
          [(link-to "/login" "login")
           (link-to "/register" "register")]))
       (form-to [:get "/search"]
                [:input {:name "q" :id "search" :class :search :value
                         "Search jars..."
                         :onclick (str "if (!this.cleared==1) {this.value=''; "
                                       "this.cleared=1;}")}])]]
     [:div {:class :clear}]]
    [:div {:class "container_12 article"}
     [:article
      body]]
    [:footer
     (link-to "https://github.com/ato/clojars-web/wiki/About" "about")
     (link-to "/projects" "projects")
     (link-to "https://github.com/ato/clojars-web/blob/master/NEWS.md" "news")
     (link-to "https://github.com/ato/clojars-web/wiki/Contact" "contact")
     (link-to "https://github.com/ato/clojars-web" "code")
     (link-to "https://github.com/ato/clojars-web/wiki/" "help")]]))

(defn error-list [errors]
  (when errors
    [:div {:class :error}
     [:strong "Blistering barnacles!"]
     "  Something's not shipshape:"
     (unordered-list errors)]))

(defn tag [s]
  (html [:span {:class "tag"} (h s)]))

(defn jar-url [jar]
  (if (= (:group_name jar) (:jar_name jar))
    (str "/" (:jar_name jar))
    (str "/" (:group_name jar) "/" (:jar_name jar))))

(defn jar-name [jar]
  (if (= (:group_name jar) (:jar_name jar))
    (h (:jar_name jar))
    (h (str (:group_name jar) "/" (:jar_name jar)))))

(defn jar-link [jar]
  (link-to (jar-url jar) (jar-name jar)))

(defn user-link [username]
  (link-to (str "/users/" username) username))

(defn group-link [groupname]
  (link-to (str "/groups/" groupname) groupname))

(defn format-date [s]
  (.format (java.text.SimpleDateFormat. "yyyy-MM-dd") s))
