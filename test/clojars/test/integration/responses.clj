(ns clojars.test.integration.responses
  (:require [clojure.test :refer :all]
            [kerodon.core :refer :all]
            [kerodon.test :refer :all]
            [clojars.test.integration.steps :refer :all]
            [clojars.web :as web]
            [clojars.test.test-helper :as help]
            [net.cgrand.enlive-html :as enlive]))

(help/use-fixtures)

(deftest respond-404
  (-> (session web/clojars-app)
      (visit "/nonexistant-route")
      (has (status? 404))
      (within [:title]
              (has (text? "Page not found | Clojars")))))

(deftest respond-405-for-puts
  (-> (session web/clojars-app)
      (visit "/nonexistant-route" :request-method :put)
      (has (status? 405))))