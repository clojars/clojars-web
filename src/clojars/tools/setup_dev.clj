(ns clojars.tools.setup-dev
  (:require [clojars.config :refer [load-config]]
            [clojars.dev.setup :refer [setup-dev-environment]])
  (:gen-class))

(defn -main [& _]
  (load-config :development)
  (setup-dev-environment))
