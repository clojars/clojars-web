(ns clojars.aws-util
  (:require
   [cognitect.aws.client.api :as aws]))

(defn- not-found-error?
  [v]
  (= :cognitect.anomalies/not-found (:cognitect.anomalies/category v)))

(defn- throw-on-error
  [v]
  (if (and (some? (:cognitect.anomalies/category v))
           (not (not-found-error? v)))
    (throw (ex-info "AWS request failed" v))
    v))

(defn invoke
  [client op request]
  (throw-on-error (aws/invoke client {:op op :request request})))

(defn not-found->nil
  [v]
  (when (not (not-found-error? v))
    v))
