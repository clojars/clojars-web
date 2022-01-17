(ns clojars.tools.build-search-index
  (:gen-class)
  (:require
   [clojars.config :refer [config]]
   [clojars.search :as search]
   [clojars.system :as system]
   [com.stuartsierra.component :as component]))

(defn -main []
  (let [system (component/start (system/base-system (config)))]
    (search/generate-index (:db system) (:stats system))))
