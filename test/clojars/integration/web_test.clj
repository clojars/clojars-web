(ns clojars.integration.web-test
  (:require [clojars.db :as db]
            [clojars.errors :as errors]
            [clojars.test-helper :as help]
            [clojure.test :refer [deftest is use-fixtures]]
            [kerodon.core :refer [fill-in follow-redirect follow press
                                  session visit within]]
            [kerodon.test :refer [has text?]]
            [net.cgrand.enlive-html :as enlive]
            [clojars.log :as log]))

(use-fixtures :each
  help/default-fixture
  help/with-clean-database)

(deftest server-errors-display-error-page
  (with-out-str (-> (session (help/app))
             (visit "/error")
                    (within [:div.small-section :> :h1]
                      (has (text? "Oops!"))))))

(deftest error-page-includes-error-id
  (with-redefs [log/trace-id (constantly #uuid "8f3788f5-9001-444d-8c57-049ba685cea8")]
    (with-out-str (-> (session (help/app))
                    (visit "/error")
                    (within [:div.small-section :> :pre.error-id]
                            (has (text? "error-id:\"8f3788f5-9001-444d-8c57-049ba685cea8\"")))))))

(deftest server-errors-log-caught-exceptions
  (let [err (atom nil)]
    (with-redefs [errors/report-error (fn [_r e & _] (reset! err e))]
      (-> (session (help/app))
        (visit "/error"))
      (is (re-find #"You really want an error" (.getMessage @err))))))

(deftest browse-page-renders-multiple-pages
  (doseq [i (range 21)]
    (db/add-jar
     help/*db*
      "test-user"
      {:name (str "tester" i) :group "tester" :version "0.1" :description "Huh" :authors ["Zz"]}))
   (-> (session (help/app))
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
  (-> (session (help/app))
      (visit "/projects")
      (fill-in "Enter a few letters..." "tester/tester123")
      (press "Jump")
      (follow-redirect)
      (within [[:ul.row enlive/last-of-type]
               [:li (enlive/nth-of-type 4)]
               [:a (enlive/nth-of-type 2)]]
              (has (text? "tester/tester123a")))))
