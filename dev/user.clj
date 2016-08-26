(ns user
  (:require [clojars
             [cloudfiles :as cf]
             [config :as config]
             [errors :as errors]
             [storage :as storage]
             [system :as system]]
            [clojars.db.migrate :as migrate]
            [clojure.java.io :as io]
            [clojure.tools.namespace.repl :refer [refresh]]
            [eftest.runner :as eftest]
            [meta-merge.core :refer [meta-merge]]
            [reloaded.repl :refer [system init stop go clear]]))

(def dev-env
  {:app {:middleware []}})


(defn new-system []
  (refresh)
  (config/configure [])
  (assoc (system/new-system (meta-merge config/config dev-env))
    :error-reporter (errors/->StdOutReporter)
    :storage (storage/multi-storage
                   (storage/fs-storage (:repo config/config))
                   ;; TODO: wrap with AsyncStorage
                   (cf/cloudfiles-storage "" "" "dev" "transient"))))

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
        (eftest/run-tests tests {:report eftest.report.pretty/report
                                 :multithread? false})))

(when (io/resource "local.clj")
  (load "local"))

(defn migrate []
  (migrate/migrate (:db config/config)))

;; TODO: function to setup fake data (from clojars.dev.setup?)

(reloaded.repl/set-init! new-system)
