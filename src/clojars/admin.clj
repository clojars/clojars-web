(ns clojars.admin
  (:require [clojars
             [config :refer [config]]
             [db :as db]
             [search :as search]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.nrepl.server :as nrepl])
  (:import java.text.SimpleDateFormat
           org.apache.commons.io.FileUtils))

(defn current-date-str []
  (.format (SimpleDateFormat. "yyyyMMdd") (db/get-time)))

(def ^:dynamic *db*)

(defn repo->backup [parts]
  (let [backup (doto (io/file (:deletion-backup-dir config))
                 (.mkdirs))
        parts' (vec (remove nil? parts))]
    (try
      (FileUtils/moveDirectory
        (apply io/file (:repo config)
                   (str/replace (first parts') "." "/") (rest parts'))
        (io/file backup (str/join "-" (conj parts' (current-date-str)))))
      (catch Exception e
        (printf "WARNING: failed to backup %s: %s\n" parts' (.getMessage e))))))

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
  (if (seq (db/group-membernames *db* group-id))
    (do
      (println "Giving you a fn to delete group" group-id)
      (fn []
        (println "Deleting" group-id)
        (repo->backup [group-id])
        (db/delete-jars *db* group-id)
        (db/delete-groups *db* group-id)
        (search/delete-from-index group-id)))
    (println "No group found under" group-id)))

(defn delete-jars [group-id jar-id & [version]]
  (let [pretty-coords (format "%s/%s %s" group-id jar-id (or version "(all versions)"))]
    (if-let [description (:description (if version
                                         (db/find-jar *db* group-id jar-id version)
                                         (db/find-jar *db* group-id jar-id)))]
      (do
        (println "Giving you a fn to delete jars that match" pretty-coords)
        (when-not
            (re-find #"(?i)delete me" description)
          (println "WARNING: jar description not set to 'delete me':" description))
        (fn []
          (println "Deleting" pretty-coords)
          (repo->backup [group-id jar-id version])
          (apply db/delete-jars *db* group-id jar-id (if version [version] []))
          (search/delete-from-index group-id jar-id)))
      (println "No artifacts found under" group-id jar-id version))))


(defn handler [mapping]
  (nrepl/default-handler
    (with-meta
      (fn [h]
        (fn [{:keys [session] :as msg}]
          (swap! session merge mapping)
          (h msg)))
      {:clojure.tools.nrepl.middleware/descriptor {:requires #{"clone"}
                                                   :expects #{"eval"}}})))

(defn init [db]
  (when-let [port (:nrepl-port config)]
    (printf "clojars-web: starting nrepl on localhost:%s\n" port)
    (nrepl/start-server :port port
                        :bind "127.0.0.1"
                        :handler (handler {#'*db* db}))))
