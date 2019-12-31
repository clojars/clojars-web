(ns clojars.unit.web-test
  (:require [clojars.test-helper :as help]
            [clojure.test :refer [deftest is use-fixtures]]
            [ring.mock.request :refer [request header]]))

(use-fixtures :each
  help/default-fixture
  help/with-clean-database)

(defn cookies [res]
  (flatten [(get-in res [:headers "Set-Cookie"])]))

(defn cookies-http-only? [res]  
  (every? #(.contains % "HttpOnly") (cookies res)))

(defn cookies-secure? [res]
  (every? #(.contains % "HttpOnly") (res cookies)))

(deftest https-cookies-are-secure
  (let [res ((help/app) (assoc (request :get "/") :scheme :https))]
    (is (cookies-secure? res))
    (is (cookies-http-only? res))))

(deftest forwarded-https-cookies-are-secure
  (let [res ((help/app) (-> (request :get "/")
                                       (header "x-forward-proto" "https")))]
    (is (cookies-secure? res))
    (is (cookies-http-only? res))))

(deftest regular-cookies-are-http-only
  (let [res ((help/app) (request :get "/"))]
    (is (cookies-http-only? res))))
