(ns clojars.test.unit.web.structured-data
  (:require [clojure.test :refer :all]
            [clojars.web.structured-data :refer :all]))

(deftest meta-name-test
  (is (nil? (meta-name "description" "")))
  (is (= [:meta {:name "description" :content "desc"}] (meta-name "description" "desc"))))

(deftest meta-property-test
  (is (nil? (meta-property "description" "")))
  (is (= [:meta {:name "description" :content "desc"}] (meta-name "description" "desc"))))

(deftest breadcrumbs-test
  (is (= "<script type=\"application/ld+json\">{\"@context\":\"http://schema.org\",\"@type\":\"BreadcrumbList\",\"itemListElement\":[{\"@type\":\"ListItem\",\"position\":1,\"item\":{\"@id\":\"https://clojars.org/groups/sky\",\"name\":\"sky\"}},{\"@type\":\"ListItem\",\"position\":2,\"item\":{\"@id\":\"https://clojars.org/sky/high\",\"name\":\"high\"}}]}</script>"
         (.to-str (breadcrumbs [{:url  "https://clojars.org/groups/sky"
                                 :name "sky"}
                                {:url  "https://clojars.org/sky/high"
                                 :name "high"}])))))
