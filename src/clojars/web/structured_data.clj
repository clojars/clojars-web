(ns clojars.web.structured-data
  "Central place for providing all structured data to search engines and crawlers
  See https://developers.google.com/structured-data/"
  (:require [cheshire.core :as json]
            [clojars.web.safe-hiccup :as hiccup]))

(def common "Common ld-json attributes"
  {"@context" "http://schema.org"
   "url"      "https://clojars.org"})

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
     "name"   "Clojars" ;; https://developers.google.com/structured-data/site-name
     "sameAs" ["https://twitter.com/clojars"] ;; https://developers.google.com/structured-data/customize/social-profiles
     "potentialAction" ;; https://developers.google.com/structured-data/slsb-overview
              {"@type"       "SearchAction"
               "target"      "https://clojars.org/search?q={search_term_string}"
               "query-input" "required name=search_term_string"}}))

(def organisation
  (ld-json
    {"@type" "Organization"
     "name"  "Clojars"
     "logo"  "https://clojars.org/images/clojars-logo@2x.png"}))
