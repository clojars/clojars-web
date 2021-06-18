(ns clojars.admin
  (:require
   [clojars.config :refer [config]]
   [clojars.db :as db]
   [clojars.file-utils :as fu]
   [clojars.search :as search]
   [clojars.storage :as storage]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.pprint :refer [pprint]]
   [clojure.string :as str]
   [clojure.tools.nrepl.server :as nrepl])
  (:import
   java.text.SimpleDateFormat))

(defn current-date-str []
  (.format (SimpleDateFormat. "yyyyMMdd") (db/get-time)))

(def ^:dynamic *db*)
(def ^:dynamic *search*)
(def ^:dynamic *storage*)

(defn backup-dir [base-dir path]
  (io/file base-dir
           (str/join "-" (conj (vec (str/split path #"/"))
                               (current-date-str)))))

(defn path->backup [path storage base-backup-dir]
  (let [ts-dir (backup-dir base-backup-dir path)]
    (run!
     (fn [p]
       (try
         (let [url (storage/artifact-url storage p)
               dest (io/file ts-dir p)]
           (.mkdirs (.getParentFile dest))
           (with-open [in (.openStream url)]
             (io/copy in dest)))
         (catch Exception e
           (printf "WARNING: failed to backup %s: %s\n" p (.getMessage e)))))
     (storage/path-seq storage path))))

(defn segments->path [segments]
  (let [[group & rest] (remove nil? segments)]
    (str/join "/" (concat [(fu/group->path group)] rest))))

(defn help []
  (println
   (str/join "\n"
             ["Admin functions - each function below returns a fn that does the actual work,"
              "and makes a backup copy of the dir before removal:"
              ""
              "* (delete-group [group-id]) - deletes a group and all of the jars in it"
              "* (delete-jars [group-id jar-id & [version]]) - deletes a jar, all versions"
              "     if no version is provided"])))

(defn delete-group [group-id]
  (if (seq (db/group-activenames *db* group-id))
    (do
      (println "Giving you a fn to delete group" group-id)
      (fn []
        (println "Deleting" group-id)
        (let [path (segments->path [group-id])]
          (path->backup path *storage* (:deletion-backup-dir (config)))
          (storage/remove-path *storage* path))
        (db/delete-jars *db* group-id)
        (db/delete-groups *db* group-id)
        (search/delete! *search* group-id)))
    (println "No group found under" group-id)))

(defn delete-jars [group-id jar-id & [version]]
  (let [pretty-coords (format "%s/%s %s" group-id jar-id (or version "(all versions)"))]
    (if-let [{:keys [description]} (if version
                                     (db/find-jar *db* group-id jar-id version)
                                     (db/find-jar *db* group-id jar-id))]
      (do
        (println "Giving you a fn to delete jars that match" pretty-coords)
        (when-not
         (and description (re-find #"(?i)delete me" description))
          (println "WARNING: jar description not set to 'delete me':" description))
        (fn []
          (println "Deleting" pretty-coords)
          (let [path (segments->path [group-id jar-id version])]
            (path->backup path *storage* (:deletion-backup-dir (config)))
            (storage/remove-path *storage* path))
          (apply db/delete-jars *db* group-id jar-id (if version [version] []))
          (when-not version (search/delete! *search* group-id jar-id))))
      (println "No artifacts found under" group-id jar-id version))))

(defn verify-group!
  "Adds the group if it doesn't exist and isn't an illegal name, then
  verifies the group if it isn't already verified. Returns the
  verification record or an error string if the group is a reserved
  name or the user doesn't have access to it."
  [username group-name]
  (let [actives (db/group-activenames *db* group-name)]
    (cond
      (and (seq actives)
           (not (some #{username} actives)))
      (format "'%s' doesn't have access to the '%s' group"
              username
              group-name)

      (contains? db/reserved-names group-name)
      (format "'%s' is a reserved name" group-name)

      (not (re-find #"[.]" group-name))
      (format "'%s' isn't a reverse domain name" group-name)

      :else
      (do
        (db/add-group *db* username group-name)
        (db/verify-group! *db* username group-name)
        (db/find-group-verification *db* group-name)))))

(defn- get-txt-records
  [domain]
  (-> (shell/sh "dig" "txt" "+short" domain)
      :out
      (str/replace "\"" "")
      (str/split #"\n")))

(defn- confirm-and-verify-group
  [username group]
  (printf "Do you want to verify %s for %s? [y/N] " group username)
  (flush)
  (let [res (read-line)]
    (if (= res "y")
      (do
        (verify-group! username group)
        (printf "\nGroup verified: https://clojars.org/groups/%s\n" group))
      (println "\nGroup *not* verified."))))

(defn check-and-verify-group!
  [group domain]
  (let [txt (get-txt-records domain)]
    (printf "TXT records for %s:\n" domain)
    (pprint txt)
    (if-some [[_ username] (some #(re-find #"^clojars (.+)$" %) txt)]
      (let [group-active-members (seq (db/group-activenames *db* group))
            group-verification (db/find-group-verification *db* group)
            user-record (db/find-user *db* username)]
        (printf "Found username %s\n" username)
        (cond
          (nil? user-record)
          (printf "User %s does not exist.\n" username)

          group-verification
          (do
            (println "Group is already verified:")
            (pprint group-verification))

          (nil? group-active-members)
          (do
            (println "Group does not exist.")
            (confirm-and-verify-group username group))

          (contains? (set group-active-members) username)
          (do
            (println "Group exists. Active members:")
            (pprint group-active-members)
            (println "User is an active member of the group.")
            (confirm-and-verify-group username group))

          :else
          (do
            (println "Group exists. Active members:")
            (pprint group-active-members)
            (println "User is *not* an active member of the group."))))
      (println "Clojars TXT record not found."))))

(defn handler [mapping]
  (nrepl/default-handler
    (with-meta
      (fn [h]
        (fn [{:keys [session] :as msg}]
          (swap! session merge mapping)
          (h msg)))
      {:clojure.tools.nrepl.middleware/descriptor {:requires #{"clone"}
                                                   :expects #{"eval"}}})))

(defn init [db search storage]
  (when-let [port (:nrepl-port (config))]
    (printf "clojars-web: starting nrepl on localhost:%s\n" port)
    (nrepl/start-server :port port
                        :bind "127.0.0.1"
                        :handler (handler {#'*db*      db
                                           #'*search*  search
                                           #'*storage* storage}))))
