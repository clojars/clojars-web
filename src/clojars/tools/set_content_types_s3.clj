(ns clojars.tools.set-content-types-s3
  "A tool to repair invalid content-types on s3. Needed since the
  original artifacts were uploaded w/o content-types."
  (:gen-class)
  (:require
   [clojars.s3 :as s3]
   [cognitect.aws.client.api :as aws]))

(defn print-status
  [{:keys [processed failed]}]
  (printf "Processed: %s (failed: %s)\n" processed failed)
  (flush))

(defn set-content-type [s3-client stats key]
  (when (= 0 (rem (:processed stats) 1000))
    (print-status stats))
  (try
    (let [{:keys [s3 bucket-name]} s3-client
          ct (s3/content-type key)]
      (aws/invoke s3
                  {:op :CopyObject
                   :request {:ContentType ct
                             :ACL "public-read"
                             :CopySource (format "/%s/%s" bucket-name key)
                             :Bucket bucket-name
                             :Key key
                             :MetadataDirective "REPLACE"}}))
    (update stats :processed inc)
    (catch Exception e
      (printf "!! Failed to set content-type for %s\n" key)
      (println e)
      (flush)
      (-> stats
          (update stats :processed inc)
          (update stats :failed inc)))))

(defn process-objects [s3-client]
  (println "Retrieving current artifact list (this may take a while)")
  (flush)
  (let [keys (s3/list-object-keys s3-client)]
    (println "Starting content-type setting")
    (flush)
    (let [stats (reduce
                 (partial set-content-type s3-client)
                 {:processed 0
                  :failed 0}
                 keys)]
      (print-status stats))))

(defn -main [& args]
  (if (not= (count args) 1)
    (println "Usage: bucket-name")
    (let [[bucket] args]
      (process-objects (s3/s3-client bucket)))))
