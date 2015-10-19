(ns clojars.dev.setup
  "Tools to setup a dev db."
  (:require [clojars.config :refer [config]]
            [clojars.db :as db]
            [clojars.search :as search]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojars.db.sql :as sql])
  (:import [org.apache.maven.artifact.repository.metadata Metadata Versioning]
           [org.apache.maven.artifact.repository.metadata.io.xpp3
            MetadataXpp3Reader
            MetadataXpp3Writer]))

(defn reset-db! []
  (sql/clear-jars! {} {:connection (:db config)})
  (sql/clear-groups!{} {:connection (:db config)})
  (sql/clear-users! {} {:connection (:db config)}))

(defn add-test-users
  "Adds n test users of the form test0/test0."
  [n]
  (mapv #(let [name (str "test" %)]
           (if (db/find-user name)
             (println "User" name "already exists")
             (do
               (printf "Adding user %s/%s\n" name name)
               (db/add-user (str name "@example.com") name name "")))
           name)
    (range n)))

(defn write-metadata [md file]
  (with-open [w (io/writer file)]
    (.write (MetadataXpp3Writer.) w md)))

(defn read-metadata [file]
  (.read (MetadataXpp3Reader.) (io/input-stream file)))

(defn create-metadata [md-file group-id artifact-id version]
  (write-metadata
    (doto (Metadata.)
      (.setGroupId group-id)
      (.setArtifactId artifact-id)
      (.setVersioning
        (doto (Versioning.)
          (.addVersion version))))
    md-file))

(defn update-metadata
  ([dir group-id artifact-id version]
   (let [md-file (io/file dir "maven-metadata.xml")]
     (if (.exists md-file)
       (update-metadata md-file version)
       (create-metadata md-file group-id artifact-id version))))
  ([md-file version]
   (-> md-file
     read-metadata
     (doto (-> .getVersioning (.addVersion version)))
     (write-metadata md-file))))

(defn import-repo
  "Builds a dev db from the contents of the repo."
  [repo stats-dir users]
  (let [group-artifact-pattern (re-pattern (str repo "/(.*)/([^/]*)$"))
        stats-file (io/file stats-dir "all.edn")]
    (->>
      (for [version-dir (file-seq (io/file repo))
            :when (and (.isDirectory version-dir)
                    (re-find #"^[0-9]\." (.getName version-dir)))
            :let [parent (.getParentFile version-dir)
                  [_ group-path artifact-id] (re-find group-artifact-pattern (.getPath parent))
                  version (.getName version-dir)
                  group-id (str/lower-case (str/replace group-path "/" "."))
                  user (or (first (db/group-membernames group-id)) (rand-nth users))]]
        (when-not (db/find-jar group-id artifact-id version)
          (printf "Importing %s/%s %s (user: %s)\n" group-id artifact-id version user)
          (db/add-jar user {:group group-id
                            :name artifact-id
                            :version version
                            :description (format "Description for %s/%s" group-id artifact-id)
                            :homepage (format "http://example.com/%s/%s" group-id artifact-id)
                            :authors ["Foo" "Bar" "Basil"]})
          (update-metadata parent group-id artifact-id version)
          [group-id artifact-id version (rand-int 1000)]))
      (remove nil?)
      (reduce (fn [accum [g a v dl]]
                (assoc-in accum [[g a] v] dl))
        {})
      pr-str
      (spit stats-file))
    (println "Wrote download stats to" (.getAbsolutePath stats-file))))

(defn -main []
  (let [db (-> config :db :subname)
        {:keys [repo stats-dir]} config]
    (println "NOTE: this will clear the contents of" db
      "and import all of the projects in" repo "into the db.\n")
    (print "Are you sure you want to continue? [y/N] ")
    (flush)
    (when-not (= "y" (.toLowerCase (read-line)))
      (println "Aborting.")
      (System/exit 1))
    (println "==> Clearing the" db "db...")
    (reset-db!)
    (println "==> Creating 10 test users...")
    (let [test-users (add-test-users 10)]
      (println "==> Importing" repo "into the db...")
      (import-repo repo stats-dir test-users))
    (println "==> Indexing" repo "...")
    (search/index-repo repo))
  ;; something (korma's thread pool?) keeps us from exiting normally
  (System/exit 0))
