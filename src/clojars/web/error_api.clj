(ns clojars.web.error-api
  (:require [ring.util.response :refer [response status content-type]]
            [cheshire.core :as json]))

(defn error-api-response [error-options error-id]
  (let [defaults {:title "Oops, we encountered an error"
                  :error-message "It seems as if an internal system error has occurred. Please give it another try. If it still doesn't work, please open an issue at https://github.com/clojars/clojars-web/issues."
                  :status 500}
        options (merge defaults error-options)
        body (json/generate-string
              {:error (:error-message options)
               :error-id error-id})
        response {:status (:status options)
                  :headers {"Content-Type" "application/json; charset=UTF-8"
                            "Access-Control-Allow-Origin" "*"}
                  :body body}]
    response))
