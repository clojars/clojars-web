(ns clojars.test.integration.web
  (:require [clojure.test :refer :all]
            [kerodon.core :refer :all]
            [kerodon.test :refer :all]
            [clojars.test.integration.steps :refer :all]
            [clojars.web :as web]
            [clojars.test.test-helper :as help]))

(help/use-fixtures)

(deftest server-errors-display-pretty-message
  (with-out-str     (-> (session web/clojars-app)
                        (visit "/error")
                        (within [:article :h1]
                                (has (text? "Oops!"))))))

(deftest server-errors-log-caught-exceptions
  (let [output (with-out-str (-> (session web/clojars-app)
                                 (visit "/error")))]
    (is (re-find #"^A server error has occured:.*$" output))))
