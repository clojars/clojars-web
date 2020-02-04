(ns clojars.s3
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [cognitect.aws.client.api :as aws]
   [cognitect.aws.credentials :as credentials])
  (:import
   (java.io ByteArrayInputStream)
   (org.apache.commons.io IOUtils)))

(defprotocol S3Bucket
  (-delete-object [client key])
  (-get-object-details [client key])
  (-get-object-stream [client key])
  (-list-objects [client prefix])
  (-put-object [client key stream opts]))

(defn- list-objects-chunk
  [client bucket-name prefix marker]
  (let [request (cond-> {:Bucket bucket-name}
                  prefix (assoc :Prefix prefix)
                  marker (assoc :Marker marker))]
    (aws/invoke client
                {:op :ListObjects
                 :request request})))

(defn- list-objects-seq
  "Generates a lazy seq of objects, chunked by the API's paging."
  [client bucket-name prefix marker]
  (let [{:keys [Contents IsTruncated]}
        (list-objects-chunk client bucket-name prefix marker)]
    (if IsTruncated
      (lazy-seq
        (concat Contents
                (list-objects-seq client bucket-name prefix
                                  (-> Contents last :Key))))
      Contents)))

(defn- strip-etag
  "ETags from the s3 api are wrapped in \"s"
  [m]
  (when m
    (update m :ETag #(str/replace % "\"" ""))))

(defn- not-found->nil
  [v]
  (when (not= :cognitect.anomalies/not-found (:cognitect.anomalies/category v))
    v))

(defrecord S3Client [s3 bucket-name]
  S3Bucket
  (-delete-object [_ key]
    (aws/invoke s3 {:op :DeleteObject
                    :request {:Bucket bucket-name
                              :Key key}}))
  (-get-object-details [_ key]
    (-> {:op :HeadObject
         :request {:Bucket bucket-name
                   :Key key}}
        (as-> % (aws/invoke s3 %))
        (not-found->nil)
        (strip-etag)))
  (-get-object-stream [_ key]
    (-> {:op :GetObject
         :request {:Bucket bucket-name
                   :Key key}}
        (as-> % (aws/invoke s3 %))
        (not-found->nil)
        :Body))
  (-list-objects [_ prefix]
    (map strip-etag (list-objects-seq s3 bucket-name prefix nil)))
  (-put-object [_ key stream opts]
    (aws/invoke s3 {:op :PutObject
                    :request (merge {:Bucket bucket-name
                                     :Key key
                                     :Body stream}
                                    opts)})))

(defn s3-client
  [access-key-id secret-access-key region bucket]
  {:pre [(not (str/blank? bucket))]}
  (let [client
        (doto
            (aws/client
              {:api :s3
               :credentials-provider (credentials/basic-credentials-provider
                                       {:access-key-id     access-key-id
                                        :secret-access-key secret-access-key})
               :region region})
          (aws/validate-requests true))]
    (->S3Client client bucket)))

(defrecord MockS3Client [state]
  S3Bucket
  (-delete-object [_ key]
    (swap! state dissoc key))
  (-get-object-details [_ key]
    (when (get @state key)
      {:Key key}))
  (-get-object-stream [_ key]
    (when-let [data (get @state key)]
      (ByteArrayInputStream. data)))
  (-list-objects [_ _prefix]
    (map (fn [[k _] ] {:Key k}) (keys @state)))
  (-put-object [_ key stream _opts]
    (swap! state assoc key (IOUtils/toByteArray stream))))

(defn mock-s3-client []
  (->MockS3Client (atom {})))

(defn delete-object
  [s3 key]
  (-delete-object s3 key))

(defn get-object-details
  [s3 key]
  (-get-object-details s3 key))

(defn object-exists?
  [s3 key]
  (boolean (get-object-details s3 key)))

(defn get-object-stream
  [s3 key]
  (-get-object-stream s3 key))

(defn list-objects
  ([s3]
   (list-objects s3 nil))
  ([s3 prefix]
   (-list-objects s3 prefix)))

(defn list-object-keys
  ([s3]
   (list-object-keys s3 nil))
  ([s3 prefix]
   (map :Key (list-objects s3 prefix))))

(defn put-object
  ([s3 key stream]
   (put-object s3 key stream nil))
  ([s3 key stream opts]
   (-put-object s3 key stream opts)))

(defn put-file
  ([s3 key f]
   (put-file s3 key f nil))
  ([s3 key f opts]
   (with-open [fis (io/input-stream f)]
     (put-object s3 key fis opts))))
