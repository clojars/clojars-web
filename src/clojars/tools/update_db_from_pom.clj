(ns clojars.tools.update-db-from-pom
  (:require [clojars.config :refer [config configure]]
            [clojars.maven :as maven]
            [clojars.db :as db]
            [clojure.java.jdbc :as jdbc]
            [clojure.java.io :as io]))

(defn pom-seq [repo]
  (for [f (file-seq repo)
        :when (and (not (re-matches #".*/\..*" (str f)))
                   (.endsWith (.getName f) ".pom"))
        :let [pom (try
                    (maven/pom-to-map f)
                    (catch Exception e (.printStackTrace e)))]
        :when pom]
    pom))

(defn update-jar [db jar {:keys [name group version scm packaging licenses dependencies]}]
  (let [existing-deps (db/find-dependencies db group name version)]
    ;; note this will lock the db during the updates, so prod will error if it tries to use it
    (jdbc/with-db-transaction
      [trans db]
      (jdbc/update! trans :jars
                    {:licenses  (when licenses (pr-str licenses))
                     :packaging (when packaging (clojure.core/name packaging))
                     :scm       (when scm (pr-str scm))}
                    ["group_name = ? AND jar_name = ? AND version = ?"
                     group name version])
      (doseq [{:keys [group_name jar_name scope] :as dep} dependencies]
        (when-not (some #(and (= group_name (:dep_group_name %))
                              (= jar_name (:dep_jar_name %)))
                        existing-deps)
          (jdbc/insert! trans :deps
                        {:group_name     group
                         :jar_name       name
                         :version        version
                         :dep_group_name group_name
                         :dep_jar_name   jar_name
                         :dep_version    (or (:version dep) "")
                         :dep_scope      scope}))))))


(defn -main [& [repo]]
  (configure nil)
  ;; add scm, licenses, packaging, dependencies
  (doseq [{:keys [name group version] :as pom} (pom-seq (io/file (or repo (:repo config))))]
    (if-let [jar (db/find-jar (:db config) group name version)]
      (update-jar (:db config) jar pom)
      (println (format "%s/%s:%s not found in db" name group version)))))
