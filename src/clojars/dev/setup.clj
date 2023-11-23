(ns clojars.dev.setup
  "Tools to setup a dev db."
  (:require
   [clojars.config :refer [config]]
   [clojars.db :as db]
   [clojars.file-utils :as fu]
   [clojars.search :as search]
   [clojars.stats :as stats]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (org.apache.maven.artifact.repository.metadata
    Metadata
    Versioning)
   (org.apache.maven.artifact.repository.metadata.io.xpp3
    MetadataXpp3Reader
    MetadataXpp3Writer)))

(defn clear-database! [db]
  (try
    (db/do-commands
     db
     ["delete from deps"
      "delete from permissions"
      "delete from jars"
      "delete from users"
      "delete from group_verifications"
      "delete from group_settings"
      "delete from audit"])
    (catch Exception _)))

(defn add-test-users
  "Adds n test users of the form test0/test0."
  [db n]
  (mapv #(let [name (str "test" %)]
           (if (db/find-user db name)
             (println "User" name "already exists")
             (do
               (printf "Adding user %s/%s\n" name name)
               (db/add-user db (str name "@example.com") name name)))
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

;; TODO: If there are more places that require this, move to a utility location.
(defn get-path
  "Ensures that / is used as separator when regex depends on it."
  [file]
  (str/replace (.getPath file) (java.io.File/separator) "/"))

(defn import-repo
  "Builds a dev db from the contents of the repo."
  [db repo stats-file users]
  (let [group-artifact-pattern (re-pattern (str repo "/(.*)/([^/]*)$"))]
    (io/make-parents stats-file)
    (->>
     (for [version-dir (file-seq (io/file repo))
           :when (and (.isDirectory version-dir)
                      (re-find #"^[0-9]\." (.getName version-dir)))
           :let [parent (.getParentFile version-dir)
                 [_ group-path artifact-id] (re-find group-artifact-pattern (get-path parent))
                 version (.getName version-dir)
                 group-id (str/lower-case (fu/path->group group-path))
                 user (or (first (db/group-adminnames db group-id)) (rand-nth users))]]
       (when-not (db/find-jar db group-id artifact-id version)
         (printf "Importing %s/%s %s (user: %s)\n" group-id artifact-id version user)
         (db/add-group db user group-id)
         (db/verify-group! db user group-id)
         (db/add-jar db
                     user {:group group-id
                           :name artifact-id
                           :version version
                           :description (format "Description for %s/%s" group-id artifact-id)
                           :homepage (format "http://example.com/%s/%s" group-id artifact-id)
                           :authors ["Foo" "Bar" "Basil"]
                           :scm {:tag "abcde1"
                                 :url (format "https://%s/%s/%s"
                                              (rand-nth ["github.com" "gitlab.com" "other.org"])
                                              group-id artifact-id)}})
         (update-metadata parent group-id artifact-id version)
         [group-id artifact-id version (rand-int 1000)]))
     (remove nil?)
     (reduce (fn [accum [g a v dl]]
               (assoc-in accum [[g a] v] dl))
             {})
     pr-str
     (spit stats-file))
    (println "Wrote download stats to" (.getAbsolutePath stats-file))))

(defn setup-dev-environment []
  (let [{:keys [repo stats-dir db]} (config)
        stats-file (io/file stats-dir "all.edn")]
    (println "NOTE: this will clear the contents of" db
             "and import all of the projects in" repo "into the db.\n")
    (print "Are you sure you want to continue? [y/N] ")
    (flush)
    (when-not (= "y" (.toLowerCase (read-line)))
      (println "Aborting.")
      (System/exit 1))
    (println "==> Clearing the" db "db...")
    (clear-database! db)
    (println "==> Creating 10 test users...")
    (let [test-users (add-test-users db 10)]
      (println "==> Importing" repo "into the db...")
      (import-repo db repo stats-file test-users))
    (println "==> Indexing...")
    (search/generate-index db (stats/local-artifact-stats stats-file))))
