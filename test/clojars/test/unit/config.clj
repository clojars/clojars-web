(ns clojars.test.unit.config
  (:require [clojars.config :as config]
            [clojure.test :refer :all]))

(deftest parse-query
  (is (= (config/parse-query "foo=bar&baz=12")
         {:foo "bar"
          :baz "12"})))

(deftest parse-mail-uri
  (is (= (config/parse-mail-uri "smtps://user:pass@host?from=a@b.c")
         {:hostname "host"
          :username "user"
          :password "pass"
          :ssl true
          :from "a@b.c"}))
  (is (= (config/parse-mail-uri "smtp://localhost:587")
         {:hostname "localhost"
          :port 587
          :ssl false})))

(deftest parse-mail
  (is (= (config/parse-mail {:hostname "x"})
         {:hostname "x"}))
  (is (= (config/parse-mail "smtp://x")
         {:hostname "x" :ssl false})))
