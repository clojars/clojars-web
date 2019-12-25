(ns clojars.integration.responses-test
  (:require[clojars.test-helper :as help]
            [clojure.test :refer [deftest use-fixtures]]
            [kerodon.core :refer [session visit within]]
            [kerodon.test :refer [has status? text?]]))

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
