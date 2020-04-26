(ns clojars.unit.certs-test
  (:require [clojure.test :refer [deftest is]]))

(def sixty-days (* 86400 1000 60))

(deftest fail-when-gpg-key-is-about-to-expire
  (let [expire-date #inst "2021-12-25T00:00:00.000-00:00"]
    (is (< (System/currentTimeMillis)
          (- (.getTime expire-date) sixty-days))
      (format "Security GPG key expires on %s" expire-date))))
