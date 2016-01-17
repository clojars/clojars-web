(ns clojars.tools.build-search-index
  (:require [clojars
             [config :refer [config configure]]
             [search :refer [index-repo]]])
  (:gen-class))

(defn -main [& [repo]]
  (configure nil)
  (index-repo (or repo (:repo config))))
