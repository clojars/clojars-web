(ns clojars.web.structured-data
  "Central place for providing all structured data to search engines and crawlers
  See https://developers.google.com/structured-data/"
  (:require [cheshire.core :as json]
            [clojars.web.safe-hiccup :as hiccup]
            [clojure.string :as str]))

(def common "Common ld-json attributes"
  {"@context" "http://schema.org"})

(defn ld-json
  "Takes a map m, converts it to JSON, and puts it inside
  an application/ld+json script tag."
  [m]
  (hiccup/raw
    (str "<script type=\"application/ld+json\">"
         (json/generate-string (merge common m))
         "</script>")))

(def website
  (ld-json
    {"@type"  "WebSite"
     "url"    "https://clojars.org"
     "name"   "Clojars" ;; https://developers.google.com/structured-data/site-name
     "sameAs" ["https://twitter.com/clojars"] ;; https://developers.google.com/structured-data/customize/social-profiles
     "potentialAction" ;; https://developers.google.com/structured-data/slsb-overview
              {"@type"       "SearchAction"
               "target"      "https://clojars.org/search?q={search_term_string}"
               "query-input" "required name=search_term_string"}}))

(def organisation
  (ld-json
    {"@type" "Organization"
     "url"   "https://clojars.org"
     "name"  "Clojars"
     "logo"  "https://clojars.org/images/clojars-logo@2x.png"}))

(defn meta-property
  "Return a meta tag if content is provided"
  [property content]
  (when-not (str/blank? content)
    [:meta {:property property :content content}]))

(defn meta-name
  "Return a meta tag if content is provided"
  [name content]
  (when-not (str/blank? content)
    [:meta {:name name :content content}]))

(defn limit-size
  [s n] 
 (if (> (count s) n)
   (str (subs s 0 (- n 3)) "...")
   s))

(defn meta-tags
  "Returns meta tags for description, twitter cards, and facebook opengraph."
  [ctx]
  (list
    ;; meta description
    (meta-name "description" (limit-size (:description ctx) 150))

    ;; twitter metadata
    [:meta {:name "twitter:card" :content "summary"}]
    [:meta {:name "twitter:site:id" :content "@clojars"}]
    [:meta {:name "twitter:site" :content "https://clojars.org"}]
    (meta-name "twitter:title" (:title ctx))
    (meta-name "twitter:description" (:description ctx))
    (meta-name "twitter:image" (or (:image-url ctx) "https://clojars.org/images/clojars-logo@2x.png"))
    (meta-name "twitter:label1" (:label1 ctx))
    (meta-name "twitter:data1" (:data1 ctx))
    (meta-name "twitter:label2" (:label2 ctx))
    (meta-name "twitter:data2" (:data2 ctx))

    ;; facebook opengraph metadata
    [:meta {:property "og:type" :content "website"}]
    [:meta {:property "og:site_name" :content "Clojars"}]
    (meta-property "og:url" (:url ctx))
    (meta-property "og:title" (:title ctx))
    (meta-property "og:description" (:description ctx))
    (meta-property "og:image" (or (:image-url ctx) "https://clojars.org/images/clojars-logo@2x.png"))))

(defn breadcrumbs [crumbs]
  (ld-json
    {"@type"           "BreadcrumbList"
     "itemListElement" (into [] (map-indexed (fn [index crumb]
                                               {"@type"    "ListItem"
                                                "position" (inc index)
                                                "item"     {"@id"  (:url crumb)
                                                            "name" (:name crumb)}}))
                             crumbs)}))
