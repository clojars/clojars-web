(ns clojars.unit.config-test
  (:require [clojars.config :as config]
            [clojure.test :refer [deftest is]]))

(deftest merge-extra-config-does-a-deep-merge
  (let [base {:db {:username "a" :password "b"}}
        extra {:db {:password "c" :ham "biscuit"}}]
    (spit "/tmp/extra.edn" (pr-str extra))
    (System/setProperty "clojars.config.file" "/tmp/extra.edn")
    (let [merged (config/merge-extra-config base)]
      (is (= {:db {:username "a"
                   :password "c"
                   :ham "biscuit"}} merged)))))
