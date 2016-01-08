(ns clojars.tools.build-search-index
  (:require [clojars
             [config :refer [config]]
             [search :refer [index-repo]]]))

(defn -main [& [repo]]
  (index-repo (or repo (config :repo))))
