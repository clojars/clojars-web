(ns clojars.unit.search-test
  (:require
   [clojars
    [search :as search]
    [stats :as stats]]
   [clojars.test-helper :as help]
   [clojure.test :refer [deftest is use-fixtures]]
   [clucy.core :as clucy]
   [com.stuartsierra.component :as component]
   [matcher-combinators.test]))

(use-fixtures :once
  help/default-fixture)

(def ^:dynamic lc nil)

(defn- lucene-search-component
  ([]
   (lucene-search-component (constantly 1)))
  ([download-counts]
   (component/start (assoc (search/lucene-component)
                           :stats (reify stats/Stats
                                    (download-count [t g a]
                                      (download-counts g a))
                                    (total-downloads [t] 100))
                           :index-factory #(clucy/memory-index)))))

(defmacro with-lucene-search-component
  [data & body]
  `(binding [lc (or lc (lucene-search-component))]
     (try
       (doseq [artifact# ~data]
         (search/index! lc artifact#))
       ~@body
       (finally
         (component/stop lc)))))

(deftest weight-by-downloads
  (let [lein-ring {:artifact-id "lein-ring"
                   :group-id "lein-ring"}
        lein-modules {:artifact-id "lein-modules"
                      :group-id "lein-modules"}
        dl-counts {["lein-ring" "lein-ring"] 2
                   ["lein-modules" "lein-modules"] 1}]
    (binding [lc (lucene-search-component
                  (fn [g a] (get dl-counts [g a])))]
      (with-lucene-search-component [lein-ring
                                     lein-modules
                                     {:artifact-id "c"
                                      :group-id "c"}]
        (is (match? [lein-modules lein-ring] (search/search lc "lein-modules" 1)))
        (is (match? [lein-ring lein-modules] (search/search lc "lein-ring" 1)))
        (is (match? [lein-ring lein-modules] (search/search lc "lein" 1)))))))

(deftest search-by-group-id
  (let [lein-ring {:group-id "lein-ring"}
        at-at {:group-id "at-at"}]
    (with-lucene-search-component [lein-ring
                                   at-at
                                   {:group-id "c"}]
      (is (match? [lein-ring] (search/search lc "lein-ring" 1)))
      (is (match? [lein-ring] (search/search lc "lein" 1)))
      (is (match? [lein-ring] (search/search lc "ring" 1)))
      (is (match? [at-at]     (search/search lc "at-at" 1))))))

(deftest search-by-artifact-id
  (let [lein-ring {:artifact-id "lein-ring"}
        at-at {:artifact-id "at-at"}]
    (with-lucene-search-component [lein-ring
                                   at-at
                                   {:artifact-id "c"}]
      (is (match? [lein-ring] (search/search lc "lein-ring" 1)))
      (is (match? [lein-ring] (search/search lc "lein" 1)))
      (is (match? [lein-ring] (search/search lc "ring" 1)))
      (is (match? [at-at]     (search/search lc "at-at" 1))))))

(deftest search-by-version
  ;; TODO is this something we really care about?
  ;; do we care about "version:..." searches?  Is version just
  ;; there to allow search page to display versions?
  ;; Same for url/authors...
  (let [lein-ring {:version "1.0.0"}]
    (with-lucene-search-component [lein-ring
                                   {:version "c"}]
      (is (match? [lein-ring] (search/search lc "1.0.0" 1))))))

(deftest search-by-description
  (let [lein-ring {:description "A Leiningen plugin that automates common Ring tasks."}]
    (with-lucene-search-component [lein-ring
                                   {:description "some completely unrelated description"}]
      (is (match? [lein-ring] (search/search lc "leiningen" 1)))
      (is (match? [lein-ring] (search/search lc "ring" 1)))
      (is (match? [lein-ring] (search/search lc "tasks" 1))))))

(deftest search-by-creation-time-in-epoch-milliseconds
  (let [lein-ring-old {:description "lein-ring old" :at #inst "2012-01-15T00:00:00Z"}
        lein-ring-new {:description "lein-ring new" :at #inst "2015-01-15T00:00:00Z"}
        lib-foo       {:description "lib-foo" :at #inst "2014-01-15T00:00:00Z"}]
    (with-lucene-search-component [lein-ring-old
                                   lein-ring-new
                                   lib-foo
                                   {:group-id "c"}]
      (is (match? (map help/at-as-time-str [lein-ring-new lib-foo])
                  (search/search lc (format "at:[%s TO %s]" (search/date-in-epoch-ms "2014-01-01T00:00:00Z") (search/date-in-epoch-ms "2016-01-01T00:00:00Z")) 1)))
      (is (match? (map help/at-as-time-str [lein-ring-new lib-foo lein-ring-old])
                  (search/search lc (str "lein " (format "at:[%s TO %s]" (search/date-in-epoch-ms "2014-01-01T00:00:00Z") (search/date-in-epoch-ms "2016-01-01T00:00:00Z"))) 1)))
      (is (match? [(help/at-as-time-str lein-ring-new)]
                  (search/search lc (str "lein AND " (format "at:[%s TO %s]" (search/date-in-epoch-ms "2014-01-01T00:00:00Z") (search/date-in-epoch-ms "2016-01-01T00:00:00Z"))) 1))))))

(deftest search-by-human-readable-creation-time
  (let [lein-ring-old {:description "lein-ring old" :at #inst "2012-01-15T00:00:00Z"}
        lein-ring-new {:description "lein-ring new" :at #inst "2015-01-15T00:00:00Z"}
        lib-foo       {:description "lib-foo" :at #inst "2014-01-15T00:00:00Z"}]
    (with-lucene-search-component [lein-ring-old
                                   lein-ring-new
                                   lib-foo
                                   {:group-id "c"}]
      (is (match? (map help/at-as-time-str [lein-ring-new lib-foo])
                  (search/search lc (format "at:[%s TO %s]" "2014-01-01T00:00:00Z" "2016-01-01T00:00:00Z") 1)))
      (is (match? (map help/at-as-time-str [lein-ring-new lib-foo lein-ring-old])
                  (search/search lc (str "lein " (format "at:[%s TO %s]" "2014-01-01T00:00:00Z" "2016-01-01T00:00:00Z")) 1)))
      (is (match? [(help/at-as-time-str lein-ring-new)]
                  (search/search lc (str "lein AND " (format "at:[%s TO %s]" "2014-01-01T00:00:00Z" "2016-01-01T00:00:00Z")) 1)))
      (is (match? []
                  (search/search lc (format "at:[%s TO %s]" "2014-01-01" "2016-FOO/BAR") 1))))))

(deftest deleting-by-group-id
  (let [lein-ring {:group-id "lein-ring"
                   :artifact-id "lein-ring"}
        another-lein-ring {:group-id "lein-ring"
                           :artifact-id "another-lein-ring"}]
    (with-lucene-search-component [lein-ring another-lein-ring]
      (search/delete! lc "lein-ring")
      (is (empty? (search/search lc "lein-ring" 1))))))

(deftest deleting-by-group-id-and-artifact-id
  (let [lein-ring {:group-id "lein-ring"
                   :artifact-id "lein-ring"}
        another-lein-ring {:group-id "lein-ring"
                           :artifact-id "another-lein-ring"}]
    (with-lucene-search-component [lein-ring another-lein-ring]
      (search/delete! lc "lein-ring" "lein-ring")
      (is (match? [another-lein-ring] (search/search lc "lein-ring" 1))))))
