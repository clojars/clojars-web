(ns clojars.tools.setup-dev
  (:require [clojars.dev.setup :refer [setup-dev-environment]]
            [clojars.config :refer [configure]])
  (:gen-class))

(defn -main [& _]
  (configure nil)
  (setup-dev-environment))
