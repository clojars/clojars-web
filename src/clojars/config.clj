(ns clojars.config
  (:require [clojure.java.io :as io]))

(def config (when-not *compile-files* (read-string (slurp (io/resource "config.clj")))))
