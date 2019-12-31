(ns user
  (:refer-clojure :exclude [test])
  (:require [clojars
             [cloudfiles :as cf]
             [config :as config]
             [errors :as errors]
             [system :as system]]
            [clojars.db.migrate :as migrate]
            [clojure.java.io :as io]
            [clojure.tools.namespace.repl :refer [refresh]]
            [eftest.runner :as eftest]
            [meta-merge.core :refer [meta-merge]]
            [reloaded.repl :refer [system init stop go clear]]))

(def dev-env
  {:app {:middleware []}})

(defn migrate []
  (migrate/migrate (:db (config/config))))

(defn new-system []
  (refresh)
  (migrate)
  (assoc (system/new-system (meta-merge (config/config) dev-env))
    :error-reporter (errors/stdout-reporter)
    :cloudfiles     (cf/connect "" "" "dev" "transient")))

(ns-unmap *ns* 'test)

(defn reset []
  (if system
    (do (clear)
        (go))
    (refresh)))

(defn test [& tests]
  (let [tests (if (empty? tests)
                  (eftest/find-tests "test")
                  tests)]
    (binding [config/*profile* "test"]
      (eftest/run-tests tests {:report eftest.report.pretty/report
                               :multithread? false}))))

(when (io/resource "local.clj")
  (load "local"))

;; TODO: function to setup fake data (from clojars.dev.setup?)

(reloaded.repl/set-init! new-system)
