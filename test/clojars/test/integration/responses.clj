(ns clojars.test.integration.responses
  (:use clojure.test
        kerodon.core
        kerodon.test
        clojars.test.integration.steps)
  (:require [clojars.web :as web]
            [clojars.test.test-helper :as help]
            [net.cgrand.enlive-html :as enlive]))

(help/use-fixtures)

(deftest respond-404
  (-> (session web/clojars-app)
      (visit "/nonexistant-route")
      (has (status? 404))
      (within [:title]
              (has (text? "Page not found | Clojars")))))