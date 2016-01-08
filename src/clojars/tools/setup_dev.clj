(ns clojars.tools.setup-dev
  (:require [clojars.dev.setup :refer [setup-dev-environment]]))

(defn -main [& _]
  (setup-dev-environment))
