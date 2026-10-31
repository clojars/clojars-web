(ns clojars.unit.web.structured-data-test
  (:require
   [clojars.web.structured-data :as structured-data]
   [clojure.test :refer [deftest is]]))

(deftest meta-name-test
  (is (nil? (structured-data/meta-name "description" "")))
  (is (= [:meta {:name "description" :content "desc"}]
         (structured-data/meta-name "description" "desc"))))

(deftest meta-property-test
  (is (nil? (structured-data/meta-property "description" "")))
  (is (= [:meta {:name "description" :content "desc"}]
         (structured-data/meta-name "description" "desc"))))

(deftest breadcrumbs-test
  (is (= "<script type=\"application/ld+json\">{\"@context\":\"http://schema.org\",\"@type\":\"BreadcrumbList\",\"itemListElement\":[{\"@type\":\"ListItem\",\"position\":1,\"item\":{\"@id\":\"https://clojars.org/groups/sky\",\"name\":\"sky\"}},{\"@type\":\"ListItem\",\"position\":2,\"item\":{\"@id\":\"https://clojars.org/sky/high\",\"name\":\"high\"}}]}</script>"
         (str (structured-data/breadcrumbs
               [{:url  "https://clojars.org/groups/sky"
                 :name "sky"}
                {:url  "https://clojars.org/sky/high"
                 :name "high"}])))))
