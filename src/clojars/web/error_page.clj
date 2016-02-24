(ns clojars.web.error-page
  (:require [clojars.web.common :refer [html-doc]]
            [ring.util.response :refer [response status content-type]]
            [hiccup.element :refer [link-to]]))

(defn error-page-response [error-options error-id]
  (let [defaults {:title "Oops, we encountered an error"
                  :error-message [:p
                                  "It seems as if an internal system error has occurred. Please give it another try. If it still doesn't work please "
                                  (link-to "https://github.com/clojars/clojars-web/issues" "open an issue")
                                  " and include:"]
                  :status 500}
        options (merge defaults error-options)]
    (-> (response (html-doc (:title options) {}
                            [:div.small-section
                             [:h1 "Oops!"]
                             (:error-message options)
                             [:p
                              [:pre.error-id (format "error-id:\"%s\"" error-id)]]]))
        (status (:status options))
        (content-type "text/html"))))
