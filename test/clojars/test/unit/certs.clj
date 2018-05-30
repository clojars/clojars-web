(ns clojars.test.unit.certs
  (:require [clojure.test :refer :all]))

(def sixty-days (* 86400 1000 60))

(deftest fail-when-gpg-key-is-about-to-expire
  (let [expire-date #inst "2019-11-28T00:00:00.000-00:00"]
    (is (< (System/currentTimeMillis)
          (- (.getTime expire-date) sixty-days))
      (format "Security GPG key expires on %s" expire-date))))

(deftest fail-when-tls-cert-is-about-to-expire
  (let [expire-date #inst "2020-06-17T00:00:00.000-00:00"]
    (is (< (System/currentTimeMillis)
          (- (.getTime expire-date) sixty-days))
      (format "clojars.org TLS cert expires on %s.\nBe sure to give lein a copy of the new one: %s"
        expire-date "https://github.com/technomancy/leiningen/blob/master/leiningen-core/resources/clojars.pem"))))
