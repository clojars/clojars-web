(ns clojars.unit.middleware-test
  (:require
   [clojars.middleware :as middleware]
   [clojure.test :refer [deftest is]]))

(def trailing-slash (middleware/wrap-ignore-trailing-slash (fn [x] (get x :uri))))

(deftest trailing-slash-doesnt-modify-root
  (is (= "/" (trailing-slash {:uri "/"}))))

(deftest trailing-slash-doesnt-modify-sub-routes
  (is (= "/artifact/project" (trailing-slash {:uri "/artifact/project"}))))

(deftest trailing-slash-removes-trailing-slash
  (is (= "/artifact/project" (trailing-slash {:uri "/artifact/project/"}))))

(deftest trailing-slash-doesnt-remove-redundant-trailing-slash
  (is (= "/artifact/project/" (trailing-slash {:uri "/artifact/project//"}))))
