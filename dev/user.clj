(ns user
  (:refer-clojure :exclude [test])
  (:require
   [clojars
    [config :as config]
    [errors :as errors]
    [system :as system]]
   [clojars.db.migrate :as migrate]
   [clojars.email :as email]
   [clojars.s3 :as s3]
   [clojure.java.io :as io]
   [clojure.tools.namespace.repl :refer [refresh]]
   [eftest.runner :as eftest]
   [reloaded.repl :refer [system init stop go clear]])
  (:import
   (java.io
    ByteArrayInputStream)))

(defn migrate []
  (migrate/migrate (:db (config/config))))

(defn new-system []
  (refresh)
  (migrate)
  (let [stats-bucket (s3/mock-s3-client)]
    (s3/put-object stats-bucket "all.edn" (ByteArrayInputStream. (.getBytes "{}")))
    (assoc (system/new-system (config/config))
           :error-reporter (errors/stdout-reporter)
           :mailer         (email/mock-mailer)
           :repo-bucket    (s3/mock-s3-client)
           :stats-bucket   stats-bucket)))

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
