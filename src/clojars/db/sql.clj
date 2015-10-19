(ns clojars.db.sql
  (:require [yesql.core :refer [defqueries]]))

;; hack due to https://github.com/krisajenkins/yesql/issues/118
(defqueries "queries/sqlite-queryfile.sql")

