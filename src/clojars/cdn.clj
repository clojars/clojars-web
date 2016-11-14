(ns clojars.cdn
  (:require [cheshire.core :as json]
            [clj-http.client :as http]))

(defn purge [key cdn-url path]
  (let [res (http/request {:method "PURGE"
                           :url (format "%/%s" cdn-url path)
                           :headers {"Fastly-Key" key}})
        body (json/parse-string (:body res) true)]
    (assoc body :http-status (:status res))))

