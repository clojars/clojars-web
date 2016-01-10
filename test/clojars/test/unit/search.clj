(ns clojars.test.unit.search
  (:require [clojars
             [search :refer :all]
             [stats :as stats]]
            [clojure.test :refer :all]
            [com.stuartsierra.component :as component]
            [clucy.core :as clucy]))

(deftest weight-by-downloads
  (let [lc (component/start (assoc (lucene-component)
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
        (index! lc data))
      (is (= (map #(dissoc % :licenses) (search lc "lein-modules" 1))
             [lein-modules lein-ring]))
      (is (= (map #(dissoc % :licenses) (search lc "lein-ring" 1))
             [lein-ring lein-modules]))
      (is (= (map #(dissoc % :licenses) (search lc "lein" 1))
             [lein-ring lein-modules]))
      (finally
        (component/stop lc)))))

(deftest search-by-group-id
  (let [lc (component/start (assoc (lucene-component)
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
        (index! lc data))
      (is (= (map #(dissoc % :licenses) (search lc "lein-ring" 1))
             [lein-ring]))
      (is (= (map #(dissoc % :licenses) (search lc "lein" 1))
             [lein-ring]))
      (is (= (map #(dissoc % :licenses) (search lc "ring" 1))
             [lein-ring]))
      (is (= (map #(dissoc % :licenses) (search lc "at-at" 1))
             [at-at]))
      (finally
        (component/stop lc)))))

(deftest search-by-artifact-id
  (let [lc (component/start (assoc (lucene-component)
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
        (index! lc data))
      (is (= (map #(dissoc % :licenses) (search lc "lein-ring" 1))
             [lein-ring]))
      (is (= (map #(dissoc % :licenses) (search lc "lein" 1))
             [lein-ring]))
      (is (= (map #(dissoc % :licenses) (search lc "ring" 1))
             [lein-ring]))
      (comment
        ;;TODO fix query parser to not ignore words #243
        (is (= (map #(dissoc % :licenses) (search lc "at-at" 1))
             [at-at])))
      (finally
        (component/stop lc)))))

(deftest search-by-version
  ;;TODO is this something we really care about?
  ;;do we care about "version:..." searches?  Is version just
  ;;there to allow search page to display versions?
  ;; Same for url/authors...
  (let [lc (component/start (assoc (lucene-component)
                                   :stats (reify stats/Stats
                                            (download-count [t g a] 1)
                                            (total-downloads [t] 100))
                                   :index-factory #(clucy/memory-index)))
        lein-ring {:version "1.0.0"}]
    (try
      (doseq [data [lein-ring
                    {:version "c"}]]
        (index! lc data))
      (is (= (map #(dissoc % :licenses) (search lc "1.0.0" 1))
             [lein-ring]))
      (finally
        (component/stop lc)))))

(deftest search-by-description
  (let [lc (component/start (assoc (lucene-component)
                                   :stats (reify stats/Stats
                                            (download-count [t g a] 1)
                                            (total-downloads [t] 100))
                                   :index-factory #(clucy/memory-index)))
        lein-ring {:description "A Leiningen plugin that automates common Ring tasks."}]
    (try
      (doseq [data [lein-ring
                    {:description "some completely unrelated description"}]]
        (index! lc data))
      (is (= (map #(dissoc % :licenses) (search lc "leiningen" 1))
             [lein-ring]))
      (is (= (map #(dissoc % :licenses) (search lc "ring" 1))
             [lein-ring]))
      (is (= (map #(dissoc % :licenses) (search lc "tasks" 1))
             [lein-ring]))
      (finally
        (component/stop lc)))))

(deftest deleting-by-group-id
  (let [lc (component/start (assoc (lucene-component)
                                   :stats (reify stats/Stats
                                            (download-count [t g a] 1)
                                            (total-downloads [t] 100))
                                   :index-factory #(clucy/memory-index)))
        lein-ring {:group-id "lein-ring"
                   :artifact-id "lein-ring"}
        another-lein-ring {:group-id "lein-ring"
                           :artifact-id "another-lein-ring"}]
    (try
      (index! lc lein-ring)
      (index! lc another-lein-ring)
      (delete! lc "lein-ring")
      (is (empty? (search lc "lein-ring" 1)))
      (finally
        (component/stop lc)))))

(deftest deleting-by-group-id-and-artifact-id
  (let [lc (component/start (assoc (lucene-component)
                                   :stats (reify stats/Stats
                                            (download-count [t g a] 1)
                                            (total-downloads [t] 100))
                                   :index-factory #(clucy/memory-index)))
        lein-ring {:group-id "lein-ring"
                   :artifact-id "lein-ring"}
        another-lein-ring {:group-id "lein-ring"
                           :artifact-id "another-lein-ring"}]
    (try
      (index! lc lein-ring)
      (index! lc another-lein-ring)
      (delete! lc "lein-ring" "lein-ring")
      (is (= (map #(dissoc % :licenses) (search lc "lein-ring" 1))
             [another-lein-ring]))
      (finally
        (component/stop lc)))))
