(ns clojars.s3
  (:require
   [cognitect.aws.client.api :as aws]
   [cognitect.aws.credentials :as credentials])
  (:import
   (java.io ByteArrayInputStream)
   (org.apache.commons.io IOUtils)))

(defprotocol S3
  (-get-object-stream [client bucket-name key])
  (-put-object [client bucket-name key stream]))

(defrecord S3Client [s3]
  S3
  (-get-object-stream [_ bucket-name key]
    (-> s3
        (aws/invoke {:op :GetObject
                     :request {:Bucket bucket-name
                               :Key key}})
        :Body))
  (-put-object [_ bucket-name key stream]
    (aws/invoke s3 {:op :PutObject
                    :request {:Bucket bucket-name
                              :Key key
                              :Body stream}})))

(defn s3-client
  [access-key-id secret-access-key region]
  (->S3Client
    (doto
        (aws/client
          {:api :s3
           :credentials-provider (credentials/basic-credentials-provider
                                   {:access-key-id     access-key-id
                                    :secret-access-key secret-access-key})
           :region region})
      (aws/validate-requests true))))

(defrecord MockS3Client [state]
  S3
  (-get-object-stream [_ bucket-name key]
    (when-let [data (get-in @state [bucket-name key])]
      (ByteArrayInputStream. data)))
  (-put-object [_ bucket-name key stream]
    (swap! state assoc-in [bucket-name key] (IOUtils/toByteArray stream))))

(defn mock-s3-client []
  (->MockS3Client (atom {})))

(defn put-object
  [s3 bucket-name key stream]
  (-put-object s3 bucket-name key stream))

(defn get-object-stream
  [s3 bucket-name key]
  (-get-object-stream s3 bucket-name key))
