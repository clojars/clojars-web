(ns clojars.tools.update-db-from-pom
  (:require
   [clojars.config :refer [config]]
   [clojars.db :as db]
   [clojars.file-utils :as fu]
   [clojars.maven :as maven]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [next.jdbc :as jdbc]
   [next.jdbc.sql :as sql]))

(defn pom-seq [repo known-paths]
  (for [f (file-seq repo)
        :when (and (not (re-matches #".*/\..*" (str f)))
                   (.endsWith (.getName f) ".pom"))
        :let [path (.getAbsolutePath f)]
        :when (not (some #{path} known-paths))
        :let [pom (try
                    (maven/pom-to-map f)
                    (catch Exception e (.printStackTrace e)))]
        :when pom
        ;; we can't look these up, so strip them
        :when (not-any? #(re-find #"^\$" (name %))
                        ((juxt :packaging :group :name :version) pom))]
    (with-meta pom
      {:pom-path path
       :last-modified (.lastModified f)})))

(defn update-jar [db {:keys [group_name jar_name version]} {:keys [scm packaging licenses dependencies]}]
  ;; note this will lock the db during the updates, so prod will
  ;; error if it tries to use it
  (jdbc/with-transaction
    [trans db]
    (sql/update! trans :jars
                 {:licenses  (when licenses (pr-str licenses))
                  :packaging (when packaging (name packaging))
                  :scm       (when scm (pr-str scm))}
                 {:group_name group_name
                  :jar_name   jar_name
                  :version    version})
    (when (seq dependencies)
      (apply sql/insert! trans :deps
             (mapv (fn [dep]
                     {:group_name     group_name
                      :jar_name       jar_name
                      :version        version
                      :dep_group_name (:group_name dep)
                      :dep_jar_name   (:jar_name dep)
                      :dep_version    (or (:version dep) "")
                      :dep_scope      (:scope dep)})
                   dependencies)))))

(defn read-data [data-file]
  (if (.exists data-file)
    (edn/read-string (slurp data-file))
    {}))

(defn write-data [data data-file]
  (spit data-file (pr-str data)))

(defn discover-id
  "Finds the id of the pom from the path, not from the pom data, since that can be a lie"
  [repo-path pom]
  (let [pom-file (-> pom meta :pom-path io/file)]
    [(->> pom-file .getParentFile .getParentFile .getParentFile .getAbsolutePath (fu/subpath repo-path) fu/path->group)
     (->> pom-file .getParentFile .getParentFile .getName)
     (->> pom-file .getParentFile .getName)]))

(defn prepare [data repo]
  (loop [n 1
         poms (pom-seq (io/file repo) (map (comp :pom-path meta) (vals data)))
         data' (transient data)]
    (if-not (seq poms)
      (persistent! data')
      (recur (inc n) (rest poms)
             (let [pom (first poms)
                   id (discover-id repo pom)]
               (when (= 0 (rem n 1000))
                 (println "Prepare: processed" n "poms"))
               (if (or (not (data' id))
                       (< (-> id data' meta :last-modified)
                          (-> pom meta :last-modified)))
                 (assoc! data' id pom)
                 data'))))))

(defn perform [db data]
  (doseq [[n [[group name version] pom]] (map-indexed vector data)]
    (when (= 0 (rem (inc n) 1000))
      (println "Perform: processed" (inc n) "poms"))
    (if-let [jar (db/find-jar db group name version)]
      (update-jar (:db (config)) jar pom)
      (println (format "%s/%s:%s not found in db" group name version)))))

(defn -main [mode data-file repo]
  (if-not (and mode repo)
    (println "Usage: [prepare|perform] data-file repo-path")
    (let [data-file (io/file data-file)]
      (println ((config) :db))
      (case mode
        "prepare" (-> data-file read-data (prepare repo) (write-data data-file))
        "perform" (->> data-file read-data (perform ((config) :db)))
        (println "Unknown mode:" mode)))))
