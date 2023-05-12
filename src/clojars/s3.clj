(ns clojars.s3
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [cognitect.aws.client.api :as aws]
   [cognitect.aws.credentials :as credentials])
  (:import
   (java.io
    ByteArrayInputStream)
   (java.util
    Date)
   (org.apache.commons.io
    IOUtils)))

(defprotocol S3Bucket
  (-delete-object [client key])
  (-get-object-details [client key])
  (-get-object-stream [client key])
  (-list-entries [client prefix])
  (-list-objects [client prefix])
  (-put-object [client key stream opts]))

(defn- throw-on-error
  [v]
  (if (some? (:cognitect.anomalies/category v))
    (throw (ex-info "S3 request failed" v))
    v))

(defn- list-objects-chunk
  [client bucket-name prefix delimeter continuation-token]
  (let [request (cond-> {:Bucket bucket-name}
                  continuation-token (assoc :ContinuationToken continuation-token)
                  delimeter          (assoc :Delimiter delimeter)
                  prefix             (assoc :Prefix prefix))]
    (throw-on-error
     (aws/invoke client
                 {:op :ListObjectsV2
                  :request request}))))

(defn- list-objects-seq
  "Generates a lazy seq of list-objects results, chunked by the API's paging."
  [client bucket-name {:as opts :keys [continuation-token delimeter prefix]}]
  (let [{:as result :keys [IsTruncated NextContinuationToken]}
        (list-objects-chunk client bucket-name prefix delimeter continuation-token)]
    (if IsTruncated
      (lazy-seq
       (cons result
             (list-objects-seq client bucket-name
                               (assoc opts :continuation-token NextContinuationToken))))
      [result])))

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
    (->> {:op :DeleteObject
          :request {:Bucket bucket-name
                    :Key key}}
         (aws/invoke s3)
         (throw-on-error)))

  (-get-object-details [_ key]
    (->> {:op :HeadObject
          :request {:Bucket bucket-name
                    :Key key}}
         (aws/invoke s3)
         (not-found->nil)
         (throw-on-error)
         (strip-etag)))

  (-get-object-stream [_ key]
    (->> {:op :GetObject
          :request {:Bucket bucket-name
                    :Key key}}
         (aws/invoke s3)
         (not-found->nil)
         (throw-on-error)
         :Body))

  (-list-entries [_ prefix]
    (sequence
     (mapcat #(concat (:CommonPrefixes %) (map strip-etag (:Contents %))))
     (list-objects-seq s3 bucket-name {:delimeter "/"
                                       :prefix prefix})))

  (-list-objects [_ prefix]
    (sequence
     (comp
      (mapcat :Contents)
      (map strip-etag))
     (list-objects-seq s3 bucket-name {:prefix prefix})))

  (-put-object [_ key stream opts]
    (->> {:op :PutObject
          :request (merge {:Bucket bucket-name
                           :Key key
                           :Body stream}
                          opts)}
         (aws/invoke s3)
         (throw-on-error))))

(defn s3-client
  ;; Credentials are derived from the instance's role when running in
  ;; production and region comes from the aws.region property, so we don't have
  ;; to set either here.
  ([bucket]
   (s3-client bucket nil))
  ;; This arity is only used directly in testing, where we use minio via docker, and we have
  ;; to override the endpoint and provide credentials
  ([bucket {:keys [credentials endpoint region]}]
   {:pre [(not (str/blank? bucket))]}
   (->S3Client
    (doto (aws/client
           (cond-> {:api :s3}
             credentials (assoc :credentials-provider (credentials/basic-credentials-provider credentials))
             endpoint    (assoc :endpoint-override endpoint)
             region      (assoc :region region)))
      (aws/validate-requests true))
    bucket)))

(defn- mock-object-entry
  [k bytes]
  {:Key          k
   :Size         (count bytes)
   :LastModified (Date.)})

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
  (-list-entries [_ prefix]
    (->> (keys @state)
         (filter (fn [k]
                   (if prefix
                     (.startsWith k prefix)
                     true)))
         (map (fn [k]
                (let [k-sans-prefix (if prefix
                                      (subs k (count prefix))
                                      k)
                      [k-segment & more] (str/split k-sans-prefix #"/")]
                  (if more
                    {:Prefix (format "%s%s/" (or prefix "") k-segment)}
                    (mock-object-entry k (get @state k))))))
         (distinct)))
  (-list-objects [_ prefix]
    (into []
          (comp
           (filter (fn [k] (if prefix (.startsWith k prefix) true)))
           (map (fn [k] (mock-object-entry k (get @state k)))))
          (keys @state)))
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

(defn list-entries
  "Lists the entries in the bucket at the level defined by prefix.

  Returns a sequence of intermixed prefix maps (of the form {:Prefix \"some/string/\"})
  and object list maps (of the form {:Key \"a-key\", :Size 123, ...}, same as
  `list-objects`).

  This is used to generate directory listings."
  [s3 prefix]
  (-list-entries s3 prefix))

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

(let [content-types
      {:asc            :txt
       :bundle         :xml
       :clj            :txt
       :eclipse-plugin :zip
       :gz             "application/gzip"
       :jar            "application/x-java-archive"
       :md5            :txt
       :pom            :xml
       :properties     :txt
       :sha1           :txt
       :txt            "text/plain"
       :xml            "application/xml"
       :zip            "application/zip"
       :unknown        "application/unknown"}]

  (defn content-type* [suffix]
    (let [ct (content-types (keyword suffix) :unknown)]
      (if (keyword? ct)
        (content-type* ct)
        ct))))

(defn content-type [key]
  (content-type* (last (str/split key #"\."))))

(defn put-file
  ([s3 key f]
   (put-file s3 key f nil))
  ([s3 key f opts]
   (with-open [fis (io/input-stream f)]
     (put-object s3 key fis
                 (assoc opts
                        :ContentType (content-type key))))))
