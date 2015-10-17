(ns clojars.test.unit.web
  (:require [clojars.web :as web]
            [clojure.test :refer :all]
            [clojars.test.test-helper :as help]
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
  (let [res ((web/clojars-app help/*db*) (assoc (request :get "/") :scheme :https))]
    (is (cookies-secure? res))
    (is (cookies-http-only? res))))

(deftest forwarded-https-cookies-are-secure
  (let [res ((web/clojars-app help/*db*) (-> (request :get "/")
                                       (header "x-forward-proto" "https")))]
    (is (cookies-secure? res))
    (is (cookies-http-only? res))))

(deftest regular-cookies-are-http-only
  (let [res ((web/clojars-app help/*db*) (request :get "/"))]
    (is (cookies-http-only? res))))
