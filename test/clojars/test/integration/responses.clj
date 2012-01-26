(ns clojars.test.integration.responses
  (:use clojure.test
        kerodon.core
        clojars.test.integration.steps)
  (:require [clojars.web :as web]
            [clojars.test.test-helper :as help]
            [net.cgrand.enlive-html :as enlive]))

(help/use-fixtures)

(deftest respond-404
  (-> (init :ring-app web/clojars-app)
      (request :get "/nonexistant-route")
      (validate (status (is= 200))
                (html #(is (= [["Page not found | Clojars"]]
                              (map :content (enlive/select % [:title]))))))))