(ns clojars.config
  (:require [clojure.java.io :as io]))

(def config (read-string (slurp (io/resource "config.clj"))))