(ns clojars.tools.build-search-index
  (:require [clojars
             [config :refer [config]]
             [search :refer [generate-index]]])
  (:gen-class))

(defn -main [& [db]]
  (generate-index (or db (:db @config))))
