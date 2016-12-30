(ns clojars.test.unit.tools.generate-feeds
  (:require [clojars.tools.generate-feeds :refer :all]
            [clojars.db :as db]
            [clojure.test :refer :all]
            [clojars.test.test-helper :as help]
            [clojure.java.io :as io]
            [clojars.maven :as maven]
            [clojars.cloudfiles :as cf]
            [clojars.file-utils :as fu]
            [clojure.string :as str])
  (:import (java.util.zip GZIPInputStream)))

(defn setup-db [f]
  (doseq [name ["fake" "test"]
          version ["0.0.3-SNAPSHOT" "0.0.3-SNAPSHOT" "0.0.1" "0.0.2"]
          :let [pom-data (maven/pom-to-map (io/resource (format "%s-%s/%s.pom" name version name)))]]
    (db/add-jar help/*db* "testuser" pom-data))
  (f))

(defn setup-cloudfiles [f]
  (let [file (io/file (io/resource "fake-0.0.1/fake.pom"))]    ;; just need some content
    (cf/put-file help/*cloudfiles* "test/test/0.0.1/test.pom" file)
    (cf/put-file help/*cloudfiles* "fake/test/0.0.2/test.pom" file)
    (cf/put-file help/*cloudfiles* "fake/test/0.0.1/test.pom" file))
  (f))

(use-fixtures :each
              help/default-fixture
              help/with-clean-database
              help/with-cloudfiles
              setup-db
              setup-cloudfiles)

(def expected-feed [{:description "FAKE"
                     :group-id    "fake"
                     :artifact-id "fake"
                     :versions    ["0.0.3-SNAPSHOT" "0.0.2" "0.0.1"]}

                    {:description "TEST"
                     :scm         {:connection           "scm:git:git://github.com/fake/test.git"
                                   :developer-connection "scm:git:ssh://git@github.com/fake/test.git"
                                   :tag                  "70470ff6ae74505bdbfe5955fca6797f613c113c"
                                   :url                  "https://github.com/fake/test"}
                     :group-id    "fake"
                     :artifact-id "test"
                     :url         "http://example.com"
                     :homepage    "http://example.com"
                     :versions    ["0.0.3-SNAPSHOT" "0.0.2" "0.0.1"]}])

(def expected-jar-list '[[fake "0.0.1"]
                         [fake "0.0.2"]
                         [fake "0.0.3-SNAPSHOT"]
                         [fake/test "0.0.1"]
                         [fake/test "0.0.2"]
                         [fake/test "0.0.3-SNAPSHOT"]])

(def expected-pom-list ["./fake/test/0.0.1/test.pom"
                        "./fake/test/0.0.2/test.pom"
                        "./test/test/0.0.1/test.pom"])

(deftest feed-generation-should-work
  (is (= expected-feed (full-feed help/*db*))))

(deftest all-jars-generation-should-work
  (is (= expected-jar-list (jar-list help/*db*))))

(deftest all-poms-generation-should-work
  (is (= expected-pom-list (pom-list help/*cloudfiles*))))

(defn verify-file-and-sums [file]
  (is (.exists file))
  (is (fu/valid-checksum-file? file :md5 :fail-if-missing))
  (is (fu/valid-checksum-file? file :sha1 :fail-if-missing)))

(defn verify-cloudfiles [cf file]
  (let [name (.getName file)]
    (is (cf/artifact-exists? cf name))
    (is (cf/artifact-exists? cf (str name ".md5")))
    (is (cf/artifact-exists? cf (str name ".sha1")))))

(deftest the-whole-enchilada
  (generate-feeds "/tmp" help/*db* help/*cloudfiles*)
  (let [feed-file (io/file "/tmp" "feed.clj.gz")]
    (verify-file-and-sums feed-file)
    (verify-cloudfiles help/*cloudfiles* feed-file)
    (let [read-feed (->> feed-file
                         (io/input-stream)
                         (GZIPInputStream.)
                         (slurp)
                         (format "[%s]")
                         (read-string))]
      (is (= expected-feed read-feed))))

  (let [pom-file (io/file "/tmp" "all-poms.txt")]
    (verify-file-and-sums pom-file)
    (verify-cloudfiles help/*cloudfiles* pom-file)
    (let [read-poms (slurp pom-file)]
      (is (= (str/join "\n" expected-pom-list) (str/trim read-poms)))))

  (let [pom-file (io/file "/tmp" "all-poms.txt.gz")]
    (verify-file-and-sums pom-file)
    (verify-cloudfiles help/*cloudfiles* pom-file)
    (let [read-poms (-> pom-file (io/input-stream) (GZIPInputStream.) (slurp))]
      (is (= (str/join "\n" expected-pom-list) (str/trim read-poms)))))

  (let [jar-file (io/file "/tmp" "all-jars.clj")]
    (verify-file-and-sums jar-file)
    (verify-cloudfiles help/*cloudfiles* jar-file)
    (let [read-jars (->> jar-file (slurp) (format "[%s]") (read-string))]
      (is (= expected-jar-list read-jars))))

  (let [jar-file (io/file "/tmp" "all-jars.clj.gz")]
    (verify-file-and-sums jar-file)
    (verify-cloudfiles help/*cloudfiles* jar-file)
    (let [read-jars (->> jar-file
                         (io/input-stream)
                         (GZIPInputStream.)
                         (slurp)
                         (format "[%s]")
                         (read-string))]
      (is (= expected-jar-list read-jars)))))
