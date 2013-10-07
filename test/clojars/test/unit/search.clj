(ns clojars.test.unit.search
  (:require [clojars.search :as search]
            [clojure.test :refer :all]
            [clojars.test.test-helper :as help]))

(use-fixtures :each help/default-fixture)

(deftest weight-by-downloads
  (help/make-download-count! {["asd" "alfred"] {"0.0.1" 1}
                              ["b" "alfred"] {"0.1.0" 5}
                              ["c" "c"] {"0.1.0" 5}})
  (help/make-index! [{:artifact-id "alfred"
                      :group-id "asd"}
                     {:artifact-id "alfred"
                      :group-id "b"}
                     {:artifact-id "c"
                      :group-id "c"}])
  (is (= (search/search "alfred")
         [{:artifact-id "alfred"
           :group-id "b"}
          {:artifact-id "alfred"
           :group-id "asd"}]))
  (is (= (search/search "asd alfred")
         [{:artifact-id "alfred"
           :group-id "asd"}
          {:artifact-id "alfred"
           :group-id "b"}])))
