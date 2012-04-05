(ns clojars.test.unit.web.common
  (:use clojure.test)
  (:require [clojars.web.common :as common]))

;;TODO: more helper tests

(deftest jar-name-uses-shortest-unique-and-html-escape
  (is (= "group/artifact" (common/jar-name {:jar_name "artifact"
                                            :group_name "group"})))
  (is (= "artifact" (common/jar-name {:jar_name "artifact"
                                      :group_name "artifact"})))
  (is (= "&lt;/alert&gt;/&lt;alert&gt;" (common/jar-name {:jar_name "<alert>"
                                           :group_name "</alert>"}))))