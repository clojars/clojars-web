(ns clojars.unit.search-test
  (:require
   [clojars
    [search :as search]
    [stats :as stats]]
   [clojars.test-helper :as help]
   [clojure.test :refer [deftest is use-fixtures]]
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
                           :index-factory help/memory-index
                           :stats (reify stats/Stats
                                    (download-count [_t g a]
                                      (download-counts g a))
                                    (total-downloads [_t] 100))))))

(defmacro with-lucene-search-component
  [data & body]
  `(binding [lc (or lc (lucene-search-component))]
     (try
       (doseq [artifact# ~data]
         (search/index! lc artifact#))
       ~@body
       (finally
         (component/stop lc)))))

(def lein-ring
  {:artifact-id "lein-ring"
   :group-id "lein-ring"})

(def lein-modules
  {:artifact-id "lein-modules"
   :group-id "lein-modules"})

(def at-at
  {:artifact-id "at-at"
   :group-id "at-at"})

(def c
  {:artifact-id "c"
   :group-id "c"})

(deftest weight-by-downloads
  (let [dl-counts {["lein-ring" "lein-ring"] 2
                   ["lein-modules" "lein-modules"] 1}]
    (binding [lc (lucene-search-component
                  (fn [g a] (get dl-counts [g a])))]
      (with-lucene-search-component [lein-ring
                                     lein-modules
                                     c]
        (is (match? [lein-ring lein-modules] (search/search lc "lein" 1)))))))

(deftest search-by-group-id
  (let [lein-ring (assoc lein-ring :artifact-id "foo")
        at-at (assoc at-at :artifact-id "foo")]
    (with-lucene-search-component [lein-ring
                                   at-at
                                   c]
      (is (match? [lein-ring] (search/search lc "lein-ring" 1)))
      (is (match? [lein-ring] (search/search lc "lein" 1)))
      (is (match? [lein-ring] (search/search lc "ring" 1)))
      (is (match? [at-at]     (search/search lc "at-at" 1))))))

(deftest search-by-artifact-id
  (let [lein-ring (assoc lein-ring :group-id "foo")
        at-at (assoc at-at :group-id "foo")]
    (with-lucene-search-component [lein-ring
                                   at-at
                                   c]
      (is (match? [lein-ring] (search/search lc "lein-ring" 1)))
      (is (match? [lein-ring] (search/search lc "lein" 1)))
      (is (match? [lein-ring] (search/search lc "ring" 1)))
      (is (match? [at-at]     (search/search lc "at-at" 1))))))

(deftest search-by-group+artifact-id-as-single-term
  (with-lucene-search-component [lein-ring
                                 at-at
                                 c]
    (is (match? [lein-ring] (search/search lc "lein-ring/lein-ring" 1)))
    (is (match? [at-at]     (search/search lc "at-at/at-at" 1)))
    (is (empty? (search/search lc "lein-ring/nope" 1)))
    (is (empty? (search/search lc "nope/lein-ring" 1)))
    (is (empty? (search/search lc "\"lein-ring/lein-ring\"" 1)))))

(deftest search-by-description
  (let [lein-ring (merge lein-ring
                         {:description "A Leiningen plugin that automates common Ring tasks."})]
    (with-lucene-search-component [lein-ring
                                   (merge c {:description "some completely unrelated description"})]
      (is (match? [lein-ring] (search/search lc "leiningen" 1)))
      (is (match? [lein-ring] (search/search lc "Leiningen" 1)))
      (is (match? [lein-ring] (search/search lc "ring" 1)))
      (is (match? [lein-ring] (search/search lc "tasks" 1)))
      (is (match? [lein-ring] (search/search lc "Tasks" 1))))))

(deftest search-by-creation-time-in-epoch-milliseconds
  (let [lein-ring-old (merge lein-ring
                             {:description "lein-ring old"
                              :at #inst "2012-01-15T00:00:00Z"})
        lein-ring-new (merge lein-ring
                             {:artifact-id "lein-ring-new"
                              :description "lein-ring new"
                              :at #inst "2015-01-15T00:00:00Z"})
        lib-foo       (merge at-at
                             {:description "lib-foo"
                              :at #inst "2014-01-15T00:00:00Z"})]
    (with-lucene-search-component [lein-ring-old
                                   lein-ring-new
                                   lib-foo
                                   c]
      (is (match? (map help/at-as-time-str [lein-ring-new lib-foo])
                  (search/search lc (format "at:[%s TO %s]" (search/date-in-epoch-ms "2014-01-01T00:00:00Z") (search/date-in-epoch-ms "2016-01-01T00:00:00Z")) 1)))
      (is (match? (map help/at-as-time-str [lein-ring-new lib-foo lein-ring-old])
                  (search/search lc (str "lein " (format "at:[%s TO %s]" (search/date-in-epoch-ms "2014-01-01T00:00:00Z") (search/date-in-epoch-ms "2016-01-01T00:00:00Z"))) 1)))
      (is (match? [(help/at-as-time-str lein-ring-new)]
                  (search/search lc (str "lein AND " (format "at:[%s TO %s]" (search/date-in-epoch-ms "2014-01-01T00:00:00Z") (search/date-in-epoch-ms "2016-01-01T00:00:00Z"))) 1))))))

(deftest search-by-human-readable-creation-time
  (let [lein-ring-old (merge lein-ring
                             {:description "lein-ring old"
                              :at #inst "2012-01-15T00:00:00Z"})
        lein-ring-new (merge lein-ring
                             {:artifact-id "lein-ring-new"
                              :description "lein-ring new"
                              :at #inst "2015-01-15T00:00:00Z"})
        lib-foo       (merge c
                             {:artifact-id "lib-foo"
                              :description "lib-foo"
                              :at #inst "2014-01-15T00:00:00Z"})]
    (with-lucene-search-component [lein-ring-old
                                   lein-ring-new
                                   lib-foo
                                   c]
      (is (match? (map help/at-as-time-str [lein-ring-new lib-foo])
                  (search/search lc (format "at:[%s TO %s]" "2014-01-01T00:00:00Z" "2016-01-01T00:00:00Z") 1)))
      (is (match? (map help/at-as-time-str [lein-ring-new lib-foo lein-ring-old])
                  (search/search lc (str "lein " (format "at:[%s TO %s]" "2014-01-01T00:00:00Z" "2016-01-01T00:00:00Z")) 1)))
      (is (match? [(help/at-as-time-str lein-ring-new)]
                  (search/search lc (str "lein AND " (format "at:[%s TO %s]" "2014-01-01T00:00:00Z" "2016-01-01T00:00:00Z")) 1)))
      (is (match? []
                  (search/search lc (format "at:[%s TO %s]" "2014-01-01" "2016-FOO") 1))))))

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
