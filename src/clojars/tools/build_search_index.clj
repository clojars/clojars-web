(ns clojars.tools.build-search-index
  (:require [clojars
             [config :refer [config configure]]
             [search :refer [generate-index]]])
  (:gen-class))

(defn -main [& [db]]
  (configure nil)
  (generate-index (or db (:db config))))
