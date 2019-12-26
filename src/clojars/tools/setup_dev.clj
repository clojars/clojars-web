(ns clojars.tools.setup-dev
  (:require [clojars.dev.setup :refer [setup-dev-environment]])
  (:gen-class))

(defn -main [& _]
  (setup-dev-environment))
