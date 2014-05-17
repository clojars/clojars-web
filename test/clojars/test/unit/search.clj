(ns clojars.test.unit.search
  (:require [clojars.search :as search]
            [clojure.test :refer :all]
            [clojars.test.test-helper :as help]))

(use-fixtures :each help/default-fixture)

(deftest weight-by-downloads
  (help/make-download-count! {["lein-ring" "lein-ring"] {"0.0.1" 10000}
                              ["lein-modules" "lein-modules"] {"0.1.0" 200}
                              ["c" "c"] {"0.1.0" 100000}})
  (help/make-index! [{:artifact-id "lein-ring"
                      :group-id "lein-ring"}
                     {:artifact-id "lein-modules"
                      :group-id "lein-modules"}
                     {:artifact-id "c"
                      :group-id "c"}])
  (is (= (search/search "lein-modules")
         [{:group-id "lein-modules", :artifact-id "lein-modules"}
          {:group-id "lein-ring", :artifact-id "lein-ring"}]))
  (is (= (search/search "lein-ring")
         [{:group-id "lein-ring", :artifact-id "lein-ring"}
          {:group-id "lein-modules", :artifact-id "lein-modules"}]))
  (is (= (search/search "lein")
         [{:group-id "lein-ring", :artifact-id "lein-ring"}
          {:group-id "lein-modules", :artifact-id "lein-modules"}]))
  (is (= (search/search "ring")
         [{:group-id "lein-ring", :artifact-id "lein-ring"}])))
