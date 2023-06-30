(ns clojars.aws-util
  (:require
   [cognitect.aws.client.api :as aws]))

(defn- throw-on-error
  [v]
  (if (some? (:cognitect.anomalies/category v))
    (throw (ex-info "AWS request failed" v))
    v))

(defn invoke
  [client op request]
  (throw-on-error (aws/invoke client {:op op :request request})))

(defn not-found->nil
  [v]
  (when (not= :cognitect.anomalies/not-found (:cognitect.anomalies/category v))
    v))
