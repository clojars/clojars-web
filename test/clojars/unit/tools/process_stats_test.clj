(ns clojars.unit.tools.process-stats-test
  (:require [clj-time.core :as time]
            [clojars.tools.process-stats :as stats]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(deftest parse-path
  (is (= {:name "haddock"
          :group "captain.archibald"
          :version "0.1.0"
          :ext "jar"}
        (stats/parse-path "/repo/captain/archibald/haddock/0.1.0/haddock-0.1.0.jar")))
  (is (= {:name "haddock"
          :group "captain.archibald"
          :version "0.1.0"
          :ext "jar"}
        (stats/parse-path "/captain/archibald/haddock/0.1.0/haddock-0.1.0.jar"))))

(def formats
  {:legacy-cdn-format "<134>2012-04-14T06:40:59Z cache-ord1741 cloudfiles-endpoint[82344]: 66.249.69.238 \"-\" \"GET /captain/archibald/haddock/0.1.0/haddock-0.1.0.jar\" 200 2377 \"(null)\" \"Java/1.6.0_30\""
   :cdn-format "<134>2012-04-14T06:40:59Z cache-bwi5023 s3-bucket[3217]: 3.90.141.179 \"GET /captain/archibald/haddock/0.1.0/haddock-0.1.0.jar HTTP/1.1\" 200 2377 \"(null)\" \"Java/1.6.0_30\""})

(deftest parse-line
  (doseq [sample-line (vals formats)]
    (let [m (stats/parse-line sample-line)]
      (is (= 200 (:status m)))
      (is (= 2377 (:size m)))
      (is (= "GET" (:method m)))
      (is (= 14 (time/day (:time m))))
      (is (= 40 (time/minute (:time m))))
      (is (= "haddock" (:name m)))
      (is (= "captain.archibald" (:group m)))
      (is (= "0.1.0" (:version m)))
      (is (= "jar" (:ext m))))))

(deftest compute-stats
  (let [stats (stats/process-log (io/resource "fake.access.log"))]
    (is (= 2 (get-in stats [["snowy" "snowy"] "0.3.0"])))
    (is (= 1 (get-in stats [["captain.archibald" "haddock"] "0.1.0"])))))
