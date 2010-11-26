(ns clojars
  (:require [clojure.java.io :as io]))

(def config
  (-> (io/reader (io/resource "clojars/config.clj"))
      (java.io.PushbackReader.)
      (read)))
