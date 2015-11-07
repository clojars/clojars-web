(ns clojars.test.unit.search
  (:require [clojars
             [search :as search]
             [stats :as stats]]
            [clojars.test.test-helper :as help]
            [clojure.test :refer :all]))

(use-fixtures :each help/default-fixture)

(deftest weight-by-downloads
  (help/make-index! [{:artifact-id "lein-ring"
                      :group-id "lein-ring"}
                     {:artifact-id "lein-modules"
                      :group-id "lein-modules"}
                     {:artifact-id "c"
                      :group-id "c"}])
  (let [stats (reify stats/Stats
                (download-count [t g a]
                  ({["lein-ring" "lein-ring"] 2
                    ["lein-modules" "lein-modules"] 1}
                   [g a]))
                (total-downloads [t] 100))]
    (is (= (search/search stats "lein-modules")
           [{:group-id "lein-modules", :artifact-id "lein-modules"}
            {:group-id "lein-ring", :artifact-id "lein-ring"}]))
    (is (= (search/search stats "lein-ring")
           [{:group-id "lein-ring", :artifact-id "lein-ring"}
            {:group-id "lein-modules", :artifact-id "lein-modules"}]))
    (is (= (search/search stats "lein")
           [{:group-id "lein-ring", :artifact-id "lein-ring"}
            {:group-id "lein-modules", :artifact-id "lein-modules"}]))
    (is (= (search/search stats "ring")
           [{:group-id "lein-ring", :artifact-id "lein-ring"}]))))
