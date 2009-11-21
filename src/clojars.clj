(ns clojars
  (:use [clojure.contrib.duck-streams :only [slurp*]]))

(def config
     (read-string (slurp* (.getResourceAsStream
                           (.getContextClassLoader (Thread/currentThread))
                           "clojars/config.clj"))))
