(ns clojars.web.error-page
  (:require [clojars.web.common :refer [html-doc]]
            [ring.util.response :refer [response status content-type]]
            [hiccup.element :refer [link-to]]))

(defn error-page-response [error-id]
  (-> (response (html-doc "Oops, we encountered an error" {}
                 [:div.small-section
                  [:h1 "Oops!"]
                  [:p
                   "It seems as if an internal system error has occurred. Please give it another try. If it still doesn't work please "
                   (link-to "https://github.com/clojars/clojars-web/issues" "open an issue")
                   " and include:"]
                  [:p
                   [:pre.error-id (format "error-id:\"%s\"" error-id)]]]))
      (status 500)
      (content-type "text/html")))
