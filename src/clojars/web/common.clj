(ns clojars.web.common
  (:require [hiccup.core :refer [html]]
            [hiccup.page :refer [include-css include-js]]
            [hiccup.element :refer [link-to unordered-list]]
            [clojars.web.safe-hiccup :refer [html5 raw form-to]]))

(defn when-ie [& contents]
  (str
   "<!--[if lt IE 9]>"
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
    (raw (when-ie (include-js "/js/html5.js")))]
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
                [:input {:name "q" :id "search" :class :search
                         :placeholder "Search jars..."}])]]
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
     (link-to "/security" "security")
     (link-to "https://github.com/ato/clojars-web/wiki/" "help")]]))

(defn flash [msg]
  (if msg
    [:div {:id "flash"}
     msg]))

(defn error-list [errors]
  (when errors
    [:div {:class :error}
     [:strong "Blistering barnacles!"]
     "  Something's not shipshape:"
     (unordered-list errors)]))

(defn tag [s]
  (raw (html [:span {:class "tag"} s])))

(defn jar-url [jar]
  (if (= (:group_name jar) (:jar_name jar))
    (str "/" (:jar_name jar))
    (str "/" (:group_name jar) "/" (:jar_name jar))))

(defn jar-name [jar]
  (if (= (:group_name jar) (:jar_name jar))
    (:jar_name jar)
    (str (:group_name jar) "/" (:jar_name jar))))

(defn jar-link [jar]
  (link-to (jar-url jar) (jar-name jar)))

(defn user-link [username]
  (link-to (str "/users/" username) username))

(defn group-link [groupname]
  (link-to (str "/groups/" groupname) groupname))

(defn format-date [s]
  (.format (java.text.SimpleDateFormat. "yyyy-MM-dd") s))

(defn simple-date [s]
  (.format (java.text.SimpleDateFormat. "MMM d, yyyy") s))

(defn page-nav [current-page total-pages]
  (let [page-range 3
        page-url "/projects?page="
        current-page (-> current-page (max 1) (min total-pages))
        main-div [:div {:class "page-nav"}]
        previous-page (if (= current-page 1)
                        [[:span {:class "previous-page disabled"} "&#8592; Previous"]]
                        [[:a
                          {:href (str page-url (- current-page 1)) :class "previous-page"}
                          "&#8592; Previous"]])
        before-current (->> (drop-while
                              #(< % 1)
                              (range (- current-page page-range) current-page))
                            (map #(link-to (str page-url %) %)))
        current [[:em {:class "current"} (str current-page)]]
        after-current (->> (take-while
                             #(<= % total-pages)
                             (range (+ current-page 1) (+ current-page 1 page-range)))
                           (map #(link-to (str page-url %) %)))
        next-page (if (= current-page total-pages)
                    [[:span {:class "next-page disabled"} "Next &#8594"]]
                    [[:a
                      {:href (str page-url (+ current-page 1)) :class "next-page"}
                      "Next &#8594"]])]
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
