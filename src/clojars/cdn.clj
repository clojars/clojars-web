(ns clojars.cdn
  (:require
   [cheshire.core :as json]
   [clojars.http-client :as http]))

(defn purge [key cdn-url path]
  (let [res (http/post (format "https://api.fastly.com/purge/%s/%s" cdn-url path)
                       {:headers {"Fastly-Key" key}})
        body (json/parse-string (:body res) true)]
    (assoc body :http-status (:status res))))

