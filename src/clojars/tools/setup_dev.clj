(ns clojars.tools.setup-dev
  (:gen-class)
  (:require
   [clojars.dev.setup :refer [setup-dev-environment]]))

(defn -main [& _]
  (setup-dev-environment))
