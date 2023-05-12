(ns clojars.unit.s3-test
  (:require
   [clojars.s3 :as s3]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [cognitect.aws.client.api :as aws]
   [matcher-combinators.matchers :as m]
   [matcher-combinators.test]))

;; Note: these tests exercise the s3 client end-to-end, and require minio to
;; running. See docker-compose.yml

(defn- throw-on-error
  [v]
  (if (some? (:cognitect.anomalies/category v))
    (throw (ex-info "S3 request failed" v))
    v))

(def ^:private real-s3-client
  (memoize
   (fn []
     (let [bucket "testing-bucket"
           client (s3/s3-client bucket
                                {:credentials {:access-key-id     "fake-access-key"
                                               :secret-access-key "fake-secret-key"}
                                 :endpoint {:protocol "http"
                                            :hostname "localhost"
                                            :port     9000}
                                 :region "us-east-1"})]
       (aws/invoke (:s3 client) {:op      :CreateBucket
                                 :request {:Bucket bucket}})
       client))))

(def ^:private page-size 1000)

(defn- clear-bucket
  []
  (let [client (real-s3-client)]
    (doseq [objects (partition-all page-size (s3/list-objects client))]
      (throw-on-error
       (aws/invoke (:s3 client)
                   {:op :DeleteObjects
                    :request {:Bucket (:bucket-name client)
                              :Delete {:Objects (map #(select-keys % [:Key]) objects)}}})))))

(use-fixtures :each (fn [f]
                      (try
                        (f)
                        (finally
                          (clear-bucket)))))

(deftest put-object+get-object-details-work
  (let [s3 (real-s3-client)]
    (s3/put-object s3 "a-key" (io/input-stream (io/resource "fake.jar")))
    (is (match?
         {:ETag          "d7c2e3e6ed5ab399efcb2fb7d8faa87c"
          :ContentLength 8
          :ContentType   "application/octet-stream"
          :AcceptRanges  "bytes"}
         (s3/get-object-details s3 "a-key")))))

(deftest list-entries-works
  (doseq [[client-type s3] [[:mock (s3/mock-s3-client)] [:real (real-s3-client)]]]
    (testing (format "With %s client type" client-type)
      (s3/put-object s3 "a-key" (io/input-stream (io/resource "fake.jar")))
      (s3/put-object s3 "nested/a-key" (io/input-stream (io/resource "fake.jar")))
      (s3/put-object s3 "nested/more/a-key" (io/input-stream (io/resource "fake.jar")))
      (is (match?
           (m/in-any-order
            [{:Prefix "nested/"}
             {:Key "a-key"}])
           (s3/list-entries s3 "")))
      (is (match?
           (m/in-any-order [{:Prefix "nested/more/"}
                            {:Key "nested/a-key"}])
           (s3/list-entries s3 "nested/"))))))

(deftest list-objects-works
  (doseq [[client-type s3] [[:mock (s3/mock-s3-client)] [:real (real-s3-client)]]]
    (testing (format "With %s client type" client-type)
      (s3/put-object s3 "a-key" (io/input-stream (io/resource "fake.jar")))
      (s3/put-object s3 "nested/a-key" (io/input-stream (io/resource "fake.jar")))
      (is (match?
           (m/in-any-order
            [{:Key "a-key"}
             {:Key "nested/a-key"}])
           (s3/list-objects s3))))))

(deftest list-objects-works-with-prefix
  (doseq [[client-type s3] [[:mock (s3/mock-s3-client)] [:real (real-s3-client)]]]
    (testing (format "With %s client type" client-type)
      (s3/put-object s3 "a-key" (io/input-stream (io/resource "fake.jar")))
      (s3/put-object s3 "nested/a-key" (io/input-stream (io/resource "fake.jar")))
      (is (match?
           [{:Key "nested/a-key"}]
           (s3/list-objects s3 "nested/"))))))

(deftest list-objects-works-with-more-than-a-page-of-objects
  (let [key-range (range (inc page-size))]
    (doseq [[client-type s3] [[:mock (s3/mock-s3-client)] [:real (real-s3-client)]]]
      (testing (format "With %s client type" client-type)
        (doseq [n key-range]
          (s3/put-object s3 (format "a-key/%s" n) (io/input-stream (io/resource "fake.jar"))))
        (is (match?
             (m/in-any-order
              (for [n key-range]
                {:Key (format "a-key/%s" n)}))
             (s3/list-objects s3)))))))

(deftest list-object-keys-works
  (doseq [[client-type s3] [[:mock (s3/mock-s3-client)] [:real (real-s3-client)]]]
    (testing (format "With %s client type" client-type)
      (s3/put-object s3 "a-key" (io/input-stream (io/resource "fake.jar")))
      (s3/put-object s3 "nested/a-key" (io/input-stream (io/resource "fake.jar")))
      (is (match?
           ["a-key" "nested/a-key"]
           (s3/list-object-keys s3))))))

(deftest list-object-keys-works-with-prefix
  (doseq [[client-type s3] [[:mock (s3/mock-s3-client)] [:real (real-s3-client)]]]
    (testing (format "With %s client type" client-type)
      (s3/put-object s3 "a-key" (io/input-stream (io/resource "fake.jar")))
      (s3/put-object s3 "nested/a-key" (io/input-stream (io/resource "fake.jar")))
      (is (match?
           ["nested/a-key"]
           (s3/list-object-keys s3 "nested/"))))))
