(ns clojars.unit.tools.generate-feeds-test
  (:require
   [clojars.db :as db]
   [clojars.file-utils :as fu]
   [clojars.maven :as maven]
   [clojars.s3 :as s3]
   [clojars.test-helper :as help]
   [clojars.tools.generate-feeds :as feeds]
   [clojure.data.xml :as xml]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is use-fixtures]]
   [matcher-combinators.matchers :as m]
   [matcher-combinators.test])
  (:import
   (java.io
    File)
   (java.util
    Date)
   (java.util.zip
    GZIPInputStream)))

(def ^:dynamic fixed-db-time nil)

(defn setup-db [f]
  (let [db-time (db/get-time)]
    (with-redefs [db/get-time (constantly db-time)]
      (doseq [name ["fake" "test"]
              version ["0.0.3-SNAPSHOT" "0.0.3-SNAPSHOT" "0.0.1" "0.0.2"]
              :let [pom-data (maven/pom-to-map (io/resource (format "%s-%s/%s.pom" name version name)))]]

        (help/add-verified-group "testuser" (:group pom-data))
        (db/add-jar help/*db* "testuser" pom-data)))
    ;; Convert to Date to reduce the precision to what we would see when reading
    ;; it from edn. java.sql.Timestamp is a subclass of Date, but has higher
    ;; precision.
    (binding [fixed-db-time (Date. (.getTime db-time))]
      (f))))

(defn setup-s3 [f]
  (let [file (io/file (io/resource "fake-0.0.1/fake.pom"))]    ;; just need some content
    (s3/put-file help/*s3-repo-bucket* "test/test/0.0.1/test.pom" file)
    (s3/put-file help/*s3-repo-bucket* "fake/test/0.0.2/test.pom" file)
    (s3/put-file help/*s3-repo-bucket* "fake/test/0.0.1/test.pom" file))
  (f))

(use-fixtures :each
  help/default-fixture
  help/with-clean-database
  help/with-s3-repo-bucket
  setup-db
  setup-s3)

(defn expected-feed
  []
  [{:description   "FAKE"
    :group-id      "fake"
    :artifact-id   "fake"
    :versions      ["0.0.3-SNAPSHOT" "0.0.2" "0.0.1"]
    :versions-meta [{:version      "0.0.3-SNAPSHOT"
                     :release-date fixed-db-time}
                    {:version      "0.0.2"
                     :release-date fixed-db-time}
                    {:version      "0.0.1"
                     :release-date fixed-db-time}]}

   {:description   "TEST"
    :scm           {:connection           "scm:git:git://github.com/fake/test.git"
                    :developer-connection "scm:git:ssh://git@github.com/fake/test.git"
                    :tag                  "70470ff6ae74505bdbfe5955fca6797f613c113c"
                    :url                  "https://github.com/fake/test"}
    :group-id      "org.clojars.dantheman"
    :artifact-id   "test"
    :url           "http://example.com"
    :homepage      "http://example.com"
    :versions      ["0.0.3-SNAPSHOT" "0.0.2" "0.0.1"]
    :versions-meta [{:version      "0.0.3-SNAPSHOT"
                     :release-date fixed-db-time
                     :scm-tag          "70470ff6ae74505bdbfe5955fca6797f613c113c"}
                    {:version      "0.0.2"
                     :release-date fixed-db-time}
                    {:version      "0.0.1"
                     :release-date fixed-db-time}]}])

(def expected-jar-list
  '[[fake "0.0.1"]
    [fake "0.0.2"]
    [fake "0.0.3-SNAPSHOT"]
    [org.clojars.dantheman/test "0.0.1"]
    [org.clojars.dantheman/test "0.0.2"]
    [org.clojars.dantheman/test "0.0.3-SNAPSHOT"]])

(def expected-pom-list
  ["./fake/test/0.0.1/test.pom"
   "./fake/test/0.0.2/test.pom"
   "./test/test/0.0.1/test.pom"])

(deftest feed-generation-should-work
  (is (match? (m/in-any-order (expected-feed)) (feeds/full-feed help/*db*))))

(deftest all-jars-generation-should-work
  (is (= expected-jar-list (feeds/jar-list help/*db*))))

(deftest all-poms-generation-should-work
  (is (= expected-pom-list (feeds/pom-list help/*s3-repo-bucket*))))

(defn verify-file-and-sums [file]
  (is (.exists file))
  (is (fu/valid-checksum-file? file :md5 :fail-if-missing))
  (is (fu/valid-checksum-file? file :sha1 :fail-if-missing)))

(defn verify-s3 [cf file]
  (let [name (.getName file)]
    (is (s3/object-exists? cf name))
    (is (s3/object-exists? cf (str name ".md5")))
    (is (s3/object-exists? cf (str name ".sha1")))))

(deftest the-whole-enchilada
  (feeds/generate-feeds "/tmp" "http://example.org" help/*db* help/*s3-repo-bucket*)
  (let [feed-file (io/file "/tmp" "feed.clj.gz")]
    (verify-file-and-sums feed-file)
    (verify-s3 help/*s3-repo-bucket* feed-file)
    (let [read-feed (->> feed-file
                         (io/input-stream)
                         (GZIPInputStream.)
                         (slurp)
                         (format "[%s]")
                         (read-string))]
      (is (match? (m/in-any-order (expected-feed)) read-feed))))

  (let [pom-file (io/file "/tmp" "all-poms.txt")]
    (verify-file-and-sums pom-file)
    (verify-s3 help/*s3-repo-bucket* pom-file)
    (let [read-poms (slurp pom-file)]
      (is (= (str/join "\n" expected-pom-list) (str/trim read-poms)))))

  (let [pom-file (io/file "/tmp" "all-poms.txt.gz")]
    (verify-file-and-sums pom-file)
    (verify-s3 help/*s3-repo-bucket* pom-file)
    (let [read-poms (-> pom-file (io/input-stream) (GZIPInputStream.) (slurp))]
      (is (= (str/join "\n" expected-pom-list) (str/trim read-poms)))))

  (let [jar-file (io/file "/tmp" "all-jars.clj")]
    (verify-file-and-sums jar-file)
    (verify-s3 help/*s3-repo-bucket* jar-file)
    (let [read-jars (->> jar-file (slurp) (format "[%s]") (read-string))]
      (is (= expected-jar-list read-jars))))

  (let [jar-file (io/file "/tmp" "all-jars.clj.gz")]
    (verify-file-and-sums jar-file)
    (verify-s3 help/*s3-repo-bucket* jar-file)
    (let [read-jars (->> jar-file
                         (io/input-stream)
                         (GZIPInputStream.)
                         (slurp)
                         (format "[%s]")
                         (read-string))]
      (is (= expected-jar-list read-jars))))

  (let [sitemap-index-file (io/file "/tmp" "sitemap.xml")
        sitemap-file (io/file "/tmp" "sitemap-0.xml")]
    (verify-file-and-sums sitemap-index-file)
    (verify-file-and-sums sitemap-file)
    (with-open [in (io/input-stream sitemap-index-file)]
      (let [sitemap-index (xml/parse in)
            first-sitemap (-> sitemap-index :content first)
            first-loc (-> first-sitemap :content first)]
        (is (= "sitemapindex" (name (:tag sitemap-index))))
        (is (some? first-sitemap))
        (is (= "sitemap" (name (:tag first-sitemap))))
        (is (some? first-loc))
        (is (= "loc" (name (:tag first-loc))))
        (is (str/ends-with? (->> first-loc :content first) (File/.getPath sitemap-file)))))
    (with-open [in (io/input-stream sitemap-file)]
      (let [sitemap (xml/parse in)
            first-url (-> sitemap :content first)
            first-loc (-> first-url :content first)]
        (is (= "urlset" (-> sitemap :tag name)))
        (is (= "url" (-> first-url :tag name)))
        (is (= "loc" (-> first-loc :tag name)))))))
