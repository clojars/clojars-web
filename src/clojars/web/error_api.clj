(ns clojars.web.error-api
  (:require
   [clojars.web.common :refer [xml-escape]]
   [cheshire.core :as json]
   [clojure.xml :as xml]))

(defn error-api-response [error-options error-id]
  (let [defaults {:status 500}
        options (merge defaults error-options)]
    (if (= :xml (:format options))
      {:status (:status options)
       :headers {"Content-Type" "text/xml; charset=UTF-8"
                 "Access-Control-Allow-Origin" "*"}
       :body (with-out-str
               (xml/emit {:tag :error
                          :attrs {:message (xml-escape (:error-message options))
                                  :id error-id}}))}
      {:status (:status options)
       :headers {"Content-Type" "application/json; charset=UTF-8"
                 "Access-Control-Allow-Origin" "*"}
       :body (json/generate-string
              {:error (:error-message options)
               :error-id error-id})})))
