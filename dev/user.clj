(ns user
  (:require [clojure.repl :refer :all]
            [clojure.pprint :refer [pprint]]
            [clojure.tools.namespace.repl :refer [refresh]]
            [clojure.java.io :as io]
            [com.stuartsierra.component :as component]
            [eftest.runner :as eftest]
            [meta-merge.core :refer [meta-merge]]
            [reloaded.repl :refer [system init start stop go reset]]
            [clojars.config :as config]
            [clojars.system :as system]
            [clojars.db.migrate :as migrate]))

(def dev-env
  {:app {:middleware []}})


(defn new-system []
  (config/configure [])
  (system/new-system (meta-merge config/config dev-env)))

(ns-unmap *ns* 'test)

(defn test [& tests]
  (let [tests (if (empty? tests)
                (eftest/find-tests "test")
                tests)]
    (eftest/run-tests tests {:report eftest.report.pretty/report
                             :multithread? false})))

(when (io/resource "local.clj")
  (load "local"))

(defn migrate []
  (migrate/migrate config/config))

;; TODO: function to setup fake data (from clojars.dev.setup?)

(reloaded.repl/set-init! new-system)
