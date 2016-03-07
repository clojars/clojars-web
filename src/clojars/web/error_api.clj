(ns clojars.web.error-api
  (:require [ring.util.response :refer [response status content-type]]
            [cheshire.core :as json]))

(defn error-api-response [error-options error-id]
  (let [defaults {:status 500}
        options (merge defaults error-options)]
    {:status (:status options)
     :headers {"Content-Type" "application/json; charset=UTF-8"
               "Access-Control-Allow-Origin" "*"}
     :body (json/generate-string
             {:error (:error-message options)
              :error-id error-id})}))
