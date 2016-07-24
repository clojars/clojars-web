(ns clojars.test.integration.responses
  (:require [clojure.test :refer :all]
            [kerodon.core :refer :all]
            [kerodon.test :refer :all]
            [clojars.test.integration.steps :refer :all]
            [clojars.web :as web]
            [clojars.test.test-helper :as help]
            [net.cgrand.enlive-html :as enlive]))

(use-fixtures :each
  help/using-test-config
  help/with-clean-database)

(deftest respond-404
  (-> (session (help/app))
      (visit "/nonexistent-route")
      (has (status? 404))
      (within [:title]
              (has (text? "Page not found - Clojars")))))

(deftest respond-404-for-non-existent-group
  (-> (session (help/app))
      (visit "/groups/nonexistent.group")
      (has (status? 404))
      (within [:title]
              (has (text? "Page not found - Clojars")))))

(deftest respond-403-for-puts
  (-> (session (help/app))
      (visit "/nonexistent-route" :request-method :put)
      (has (status? 403))))
