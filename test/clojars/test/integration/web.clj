(ns clojars.test.integration.web
  (:require [clojure.test :refer :all]
            [kerodon.core :refer :all]
            [kerodon.test :refer :all]
            [clojars.test.integration.steps :refer :all]
            [clojars.web :as web]
            [clojars.db :as db]
            [clojars.test.test-helper :as help]
            [net.cgrand.enlive-html :as enlive]))

(use-fixtures :each
  help/default-fixture
  help/with-clean-database)

(deftest server-errors-display-error-page
  (with-out-str (-> (session (web/clojars-app help/*db*))
             (visit "/error")
                    (within [:div.small-section :> :h1]
                      (has (text? "Oops!"))))))

(deftest error-page-includes-error-id
  (with-redefs [clojars.errors/error-id (constantly "ERROR")]
    (with-out-str (-> (session (web/clojars-app help/*db*))
                    (visit "/error")
                    (within [:div.small-section :> :pre.error-id]
                      (has (text? "error-id:\"ERROR\"")))))))

(deftest server-errors-log-caught-exceptions
  (let [err (atom nil)]
    (with-redefs [clojars.errors/report-error (fn [e & _] (reset! err e))]
      (-> (session (web/clojars-app help/*db*))
        (visit "/error"))
      (is (re-find #"You really want an error" (.getMessage @err))))))

(deftest browse-page-renders-multiple-pages
  (doseq [i (range 21)]
    (db/add-jar
     help/*db*
      "test-user"
      {:name (str "tester" i) :group "tester" :version "0.1" :description "Huh" :authors ["Zz"]}))
   (-> (session (web/clojars-app help/*db*))
     (visit "/projects")
     (within [:div.light-article :> :h1]
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

(deftest browse-page-can-jump
  (doseq [i (range 100 125)]
    (db/add-jar
     help/*db*
      "test-user"
      {:name (str "tester" i "a") :group "tester" :version "0.1" :description "Huh" :authors ["Zz"]}))
  (-> (session (web/clojars-app help/*db*))
      (visit "/projects")
      (fill-in "Enter a few letters..." "tester/tester123")
      (press "Jump")
      (follow-redirect)
      (within [[:ul.row enlive/last-of-type]
               [:li (enlive/nth-of-type 4)]
               [:a (enlive/nth-of-type 2)]]
              (has (text? "tester/tester123a")))))
