(ns clojars.unit.search-test
  (:require [clojars
             [search :as search]
             [stats :as stats]]
            [clojars.test-helper :as help]
            [clojure.test :refer [deftest is use-fixtures]]
            [clucy.core :as clucy]
            [com.stuartsierra.component :as component]))

(use-fixtures :once
  help/default-fixture)

(deftest weight-by-downloads
  (let [lc (component/start (assoc (search/lucene-component)
                                   :stats (reify stats/Stats
                                            (download-count [t g a]
                                              ({["lein-ring" "lein-ring"] 2
                                                ["lein-modules" "lein-modules"] 1}
                                               [g a]))
                                            (total-downloads [t] 100))
                                   :index-factory #(clucy/memory-index)))
        lein-ring {:artifact-id "lein-ring"
                   :group-id "lein-ring"}
        lein-modules {:artifact-id "lein-modules"
                      :group-id "lein-modules"}]
    (try
      (doseq [data [lein-ring
                    lein-modules
                    {:artifact-id "c"
                     :group-id "c"}]]
        (search/index! lc data))
      (is (= (map #(dissoc % :licenses) (search/search lc "lein-modules" 1))
             [lein-modules lein-ring]))
      (is (= (map #(dissoc % :licenses) (search/search lc "lein-ring" 1))
             [lein-ring lein-modules]))
      (is (= (map #(dissoc % :licenses) (search/search lc "lein" 1))
             [lein-ring lein-modules]))
      (finally
        (component/stop lc)))))

(deftest search-by-group-id
  (let [lc (component/start (assoc (search/lucene-component)
                                   :stats (reify stats/Stats
                                            (download-count [t g a] 1)
                                            (total-downloads [t] 100))
                                   :index-factory #(clucy/memory-index)))
        lein-ring {:group-id "lein-ring"}
        at-at {:group-id "at-at"}]
    (try
      (doseq [data [lein-ring
                    at-at
                    {:group-id "c"}]]
        (search/index! lc data))
      (is (= (map #(dissoc % :licenses) (search/search lc "lein-ring" 1))
             [lein-ring]))
      (is (= (map #(dissoc % :licenses) (search/search lc "lein" 1))
             [lein-ring]))
      (is (= (map #(dissoc % :licenses) (search/search lc "ring" 1))
             [lein-ring]))
      (is (= (map #(dissoc % :licenses) (search/search lc "at-at" 1))
             [at-at]))
      (finally
        (component/stop lc)))))

(deftest search-by-artifact-id
  (let [lc (component/start (assoc (search/lucene-component)
                                   :stats (reify stats/Stats
                                            (download-count [t g a] 1)
                                            (total-downloads [t] 100))
                                   :index-factory #(clucy/memory-index)))
        lein-ring {:artifact-id "lein-ring"}
        at-at {:artifact-id "at-at"}]
    (try
      (doseq [data [lein-ring
                    at-at
                    {:artifact-id "c"}]]
        (search/index! lc data))
      (is (= (map #(dissoc % :licenses) (search/search lc "lein-ring" 1))
             [lein-ring]))
      (is (= (map #(dissoc % :licenses) (search/search lc "lein" 1))
             [lein-ring]))
      (is (= (map #(dissoc % :licenses) (search/search lc "ring" 1))
             [lein-ring]))
      (comment
        ;;TODO fix query parser to not ignore words #243
        (is (= (map #(dissoc % :licenses) (search/search lc "at-at" 1))
             [at-at])))
      (finally
        (component/stop lc)))))

(deftest search-by-version
  ;;TODO is this something we really care about?
  ;;do we care about "version:..." searches?  Is version just
  ;;there to allow search page to display versions?
  ;; Same for url/authors...
  (let [lc (component/start (assoc (search/lucene-component)
                                   :stats (reify stats/Stats
                                            (download-count [t g a] 1)
                                            (total-downloads [t] 100))
                                   :index-factory #(clucy/memory-index)))
        lein-ring {:version "1.0.0"}]
    (try
      (doseq [data [lein-ring
                    {:version "c"}]]
        (search/index! lc data))
      (is (= (map #(dissoc % :licenses) (search/search lc "1.0.0" 1))
             [lein-ring]))
      (finally
        (component/stop lc)))))

(deftest search-by-description
  (let [lc (component/start (assoc (search/lucene-component)
                                   :stats (reify stats/Stats
                                            (download-count [t g a] 1)
                                            (total-downloads [t] 100))
                                   :index-factory #(clucy/memory-index)))
        lein-ring {:description "A Leiningen plugin that automates common Ring tasks."}]
    (try
      (doseq [data [lein-ring
                    {:description "some completely unrelated description"}]]
        (search/index! lc data))
      (is (= (map #(dissoc % :licenses) (search/search lc "leiningen" 1))
             [lein-ring]))
      (is (= (map #(dissoc % :licenses) (search/search lc "ring" 1))
             [lein-ring]))
      (is (= (map #(dissoc % :licenses) (search/search lc "tasks" 1))
             [lein-ring]))
      (finally
        (component/stop lc)))))

(deftest search-by-creation-time-in-epoch-milliseconds
  (let [lc (component/start (assoc (search/lucene-component)
                                   :stats (reify stats/Stats
                                            (download-count [t g a] 1)
                                            (total-downloads [t] 100))
                                   :index-factory #(clucy/memory-index)))
        lein-ring-old {:description "lein-ring old" :at (help/date-from-iso-8601-str "2012-01-15T00:00:00Z")}
        lein-ring-new {:description "lein-ring new" :at (help/date-from-iso-8601-str "2015-01-15T00:00:00Z")}
        lib-foo       {:description "lib-foo" :at (help/date-from-iso-8601-str "2014-01-15T00:00:00Z")}]
    (try
      (doseq [data [lein-ring-old
                    lein-ring-new
                    lib-foo
                    {:group-id "c"}]]
        (search/index! lc data))
      (is (= (map help/at-as-time-str [lein-ring-new lib-foo])
             (map #(dissoc % :licenses)
                  (search/search lc (format "at:[%s TO %s]" (search/date-in-epoch-ms "2014-01-01T00:00:00Z") (search/date-in-epoch-ms "2016-01-01T00:00:00Z")) 1))))
      (is (= (map help/at-as-time-str [lein-ring-new lib-foo lein-ring-old])
             (map #(dissoc % :licenses)
                     (search/search lc (str "lein " (format "at:[%s TO %s]" (search/date-in-epoch-ms "2014-01-01T00:00:00Z") (search/date-in-epoch-ms "2016-01-01T00:00:00Z"))) 1))))
      (is (= [(help/at-as-time-str lein-ring-new)]
             (map #(dissoc % :licenses)
                  (search/search lc (str "lein AND " (format "at:[%s TO %s]" (search/date-in-epoch-ms "2014-01-01T00:00:00Z") (search/date-in-epoch-ms "2016-01-01T00:00:00Z"))) 1))))
      (finally
        (component/stop lc)))))

(deftest search-by-human-readable-creation-time
  (let [lc (component/start (assoc (search/lucene-component)
                                   :stats (reify stats/Stats
                                            (download-count [t g a] 1)
                                            (total-downloads [t] 100))
                                   :index-factory #(clucy/memory-index)))
        lein-ring-old {:description "lein-ring old" :at (help/date-from-iso-8601-str "2012-01-15T00:00:00Z")}
        lein-ring-new {:description "lein-ring new" :at (help/date-from-iso-8601-str "2015-01-15T00:00:00Z")}
        lib-foo       {:description "lib-foo" :at (help/date-from-iso-8601-str "2014-01-15T00:00:00Z")}]
    (try
      (doseq [data [lein-ring-old
                    lein-ring-new
                    lib-foo
                    {:group-id "c"}]]
        (search/index! lc data))
      (is (= (map help/at-as-time-str [lein-ring-new lib-foo])
             (map #(dissoc % :licenses)
                  (search/search lc (format "at:[%s TO %s]" "2014-01-01T00:00:00Z" "2016-01-01T00:00:00Z") 1))))
      (is (= (map help/at-as-time-str [lein-ring-new lib-foo lein-ring-old])
             (map #(dissoc % :licenses)
                  (search/search lc (str "lein " (format "at:[%s TO %s]" "2014-01-01T00:00:00Z" "2016-01-01T00:00:00Z")) 1))))
      (is (= [(help/at-as-time-str lein-ring-new)]
             (map #(dissoc % :licenses)
                  (search/search lc (str "lein AND " (format "at:[%s TO %s]" "2014-01-01T00:00:00Z" "2016-01-01T00:00:00Z")) 1))))
      (is (= []
             (map #(dissoc % :licenses)
                  (search/search lc (format "at:[%s TO %s]" "2014-01-01" "2016-FOO/BAR") 1))))
      (finally
        (component/stop lc)))))

(deftest deleting-by-group-id
  (let [lc (component/start (assoc (search/lucene-component)
                                   :stats (reify stats/Stats
                                            (download-count [t g a] 1)
                                            (total-downloads [t] 100))
                                   :index-factory #(clucy/memory-index)))
        lein-ring {:group-id "lein-ring"
                   :artifact-id "lein-ring"}
        another-lein-ring {:group-id "lein-ring"
                           :artifact-id "another-lein-ring"}]
    (try
      (search/index! lc lein-ring)
      (search/index! lc another-lein-ring)
      (search/delete! lc "lein-ring")
      (is (empty? (search/search lc "lein-ring" 1)))
      (finally
        (component/stop lc)))))

(deftest deleting-by-group-id-and-artifact-id
  (let [lc (component/start (assoc (search/lucene-component)
                                   :stats (reify stats/Stats
                                            (download-count [t g a] 1)
                                            (total-downloads [t] 100))
                                   :index-factory #(clucy/memory-index)))
        lein-ring {:group-id "lein-ring"
                   :artifact-id "lein-ring"}
        another-lein-ring {:group-id "lein-ring"
                           :artifact-id "another-lein-ring"}]
    (try
      (search/index! lc lein-ring)
      (search/index! lc another-lein-ring)
      (search/delete! lc "lein-ring" "lein-ring")
      (is (= (map #(dissoc % :licenses) (search/search lc "lein-ring" 1))
             [another-lein-ring]))
      (finally
        (component/stop lc)))))
