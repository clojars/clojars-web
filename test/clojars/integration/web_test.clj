(ns clojars.integration.web-test
  (:require
   [clj-http.client :as http]
   [clojars.db :as db]
   [clojars.errors :as errors]
   [clojars.log :as log]
   [clojars.test-helper :as help]
   [clojars.web.dashboard :as dashboard]
   [clojure.test :refer [deftest is use-fixtures]]
   [kerodon.core :refer [fill-in follow-redirect follow press
                         session visit within]]
   [kerodon.test :refer [has text?]]
   [matcher-combinators.test]
   [net.cgrand.enlive-html :as enlive]))

(use-fixtures :each
  help/default-fixture
  help/with-clean-database
  help/run-test-app)

(deftest server-errors-display-error-page
  (with-redefs [dashboard/index-page (fn [& _] (throw (Exception. "BOOM!")))
                log/trace-id (constantly #uuid "8f3788f5-9001-444d-8c57-049ba685cea8")]
    (with-out-str (-> (session (help/app))
                      (visit "/")
                      (within [:div.small-section :> :h1]
                        (has (text? "Oops!")))
                      (within [:div.small-section :> :pre.error-id]
                        (has (text? "error-id:\"8f3788f5-9001-444d-8c57-049ba685cea8\"")))))))

(deftest server-errors-log-caught-exceptions
  (let [err (atom nil)]
    (with-redefs [dashboard/index-page (fn [& _] (throw (Exception. "BOOM!")))
                  errors/report-error (fn [_r e & _] (reset! err e))]
      (-> (session (help/app))
          (visit "/"))
      (is (re-find #"BOOM" (.getMessage @err))))))

(defn- search-request
  [param-string]
  (-> (format "http://localhost:%s/search?%s"
              help/test-port
              param-string)
      (http/get {:throw-exceptions false})))

(deftest invalid-params-are-rejected
  (is (match?
       {:status 400
        :body "{:schema [:map-of :keyword :string], :value {:q {:a \"b\"}}, :errors ({:path [1], :in [:q], :schema :string, :value {:a \"b\"}})}"}
       (search-request "q[a]=b")))

  (is (match?
       {:status 400
        :body "{:schema [:map-of :keyword :string], :value {:q [\"b\"]}, :errors ({:path [1], :in [:q], :schema :string, :value [\"b\"]})}"}
       (search-request "q[]=b"))))

(deftest browse-page-renders-multiple-pages
  (help/add-verified-group "test-user" "tester")
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
  (help/add-verified-group "test-user" "tester")
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
               [:div enlive/first-of-type]
               [:div enlive/first-of-type]
               [:a enlive/first-of-type]]
        (has (text? "tester/tester123a")))))
