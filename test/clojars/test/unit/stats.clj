(ns clojars.test.unit.stats
  (:use clojure.test)
  (:require [clojars.stats :as stats]
            [clj-time.core :as time]))

(deftest parse-path
  (is (= {:name "haddock"
          :group "captain.archibald"
          :version "0.1.0"
          :ext "jar"}
         (stats/parse-path "/repo/captain/archibald/haddock/0.1.0/haddock-0.1.0.jar"))))

(def sample-line "127.0.0.1 - - [14/Apr/2012:06:40:59 +0000] \"GET /repo/captain/archibald/haddock/0.1.0/haddock-0.1.0.jar HTTP/1.1\" 200 2377 \"-\" \"Java/1.6.0_30\"")

(deftest parse-clf
  (let [m (stats/parse-clf sample-line)]
    (is (= 200 (:status m)))
    (is (= 2377 (:size m)))
    (is (= "GET" (:method m)))
    (is (= 14 (time/day (:time m))))
    (is (= 40 (time/minute (:time m))))
    (is (= "haddock" (:name m)))
    (is (= "captain.archibald" (:group m)))
    (is (= "0.1.0" (:version m)))
    (is (= "jar" (:ext m)))))