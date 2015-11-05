(ns clojars.db.sql
  (:require [yesql.core :refer [defqueries]]
            [clj-time.jdbc]))

;; hack due to https://github.com/krisajenkins/yesql/issues/118
(defqueries "queries/sqlite-queryfile.sql")

