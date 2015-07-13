(ns clojars.web.error-page
  (:require [clojars.web.common :refer [html-doc]]
            [ring.util.response :refer [response status content-type]]
            [hiccup.element :refer [link-to]]
            [clj-stacktrace.repl :refer [pst]]))

(defn error-page-response [throwable]
  (-> (response (html-doc nil
                 "Oops, we encountered an error"
                 [:div.small-section
                  [:h1 "Oops!"]
                  [:p
                   "It seems as if an internal system error has occurred. Please give it another try. If it still doesn't work please "
                   (link-to "https://github.com/ato/clojars-web/issues" "open an issue.")]
                  [:p "Including the following stack trace would also be helpful."]
                  [:pre.stacktrace (with-out-str (pst throwable))]]))
      (status 500)
      (content-type "text/html")))
