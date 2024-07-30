(ns clojars.unit.web.jar-test
  (:require
   [clojars.test-helper :as help]
   [clojars.web.jar :as jar]
   [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :each
  help/default-fixture
  help/with-clean-database)

(deftest bad-homepage-url-shows-as-text
  (let [html (jar/show-jar help/*db*
                           (help/no-stats)
                           nil
                           {:homepage "something thats not a url"
                            :created 3
                            :version "1"
                            :group_name "test"
                            :jar_name "test"}
                           []
                           0)]
    (is (re-find #"something thats not a url" html))))

(deftest pages-are-escaped
  (let [html (jar/show-jar help/*db*
                           (help/no-stats)
                           nil
                           {:homepage nil
                            :created 3
                            :version "<script>alert('hi')</script>"
                            :group_name "test"
                            :jar_name "test"}
                           []
                           0)]
    (is (not (.contains html "<script>alert('hi')</script>")))))

(deftest groups-are-converted-to-paths
  (let [html (jar/show-versions nil
                                {:homepage "whatever"
                                 :created 3
                                 :version "1"
                                 :group_name "test.foo"
                                 :jar_name "test"}
                                ["1"])]
    (is (re-find #"/test/foo/test" html))))

(deftest cljdoc-uri-test
  (is (= "https://cljdoc.org/d/test.foo/test/1"
         (str (jar/cljdoc-uri {:version    "1"
                               :group_name "test.foo"
                               :jar_name   "test"}))))
  (is (= "https://cljdoc.org/d/test.foo/test/%3Cscript%3Ealert('hi')%3C/script%3E"
         (str (jar/cljdoc-uri {:version    "<script>alert('hi')</script>"
                               :group_name "test.foo"
                               :jar_name   "test"})))))
