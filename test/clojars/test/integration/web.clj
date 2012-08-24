(ns clojars.test.integration.web
  (:require [clojure.test :refer :all]
            [kerodon.core :refer :all]
            [kerodon.test :refer :all]
            [clojars.test.integration.steps :refer :all]
            [clojars.web :as web]
            [clojars.db :as db]
            [clojars.test.test-helper :as help]))

(help/use-fixtures)

(deftest server-errors-display-pretty-message
  (with-out-str (-> (session web/clojars-app)
                    (visit "/error")
                    (within [:article :h1]
                            (has (text? "Oops!"))))))

(deftest server-errors-log-caught-exceptions
  (let [output (with-out-str (-> (session web/clojars-app)
                                 (visit "/error")))]
    (is (re-find #"^A server error has occured:.*" output))))

(deftest browse-page-renders-multiple-pages
  (doseq [i (range 21)]
    (db/add-jar
      "test-user"
      {:name (str "tester" i) :group "tester" :version "0.1" :description "Huh" :authors ["Zz"]}))
   (-> (session web/clojars-app)
     (visit "/projects")
     (within [:article :h1]
             (has (text? "All projects")))
     (within [:.page-description]
             (has (text? "Displaying projects 1 - 20 of 21")))
     (within [:.page-nav :.current]
             (has (text? "1")))
     (within [:span.desc]
             (has (text? (reduce str (repeat 20 "Huh")))))

     (follow "2")
     (within [:.page-description]
             (has (text? "Displaying projects 21 - 21 of 21")))
     (within [:span.desc]
             (has (text? "Huh")))
     (within [:.page-nav :.current]
             (has (text? "2")))))
