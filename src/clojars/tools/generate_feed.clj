(ns clojars.tools.generate-feed
  (:require [clojure.java.io :as io]
            [clojars.maven :as maven]
            [clojars.config :refer [config configure]]
            [clojure.set :as set]
            [clojars.db :as db])
  (:import java.util.zip.GZIPOutputStream
           (java.io FileOutputStream PrintWriter))
  (:gen-class))

(defn full-feed [db]
  (let [grouped-jars (->> (db/all-jars db)
                          (map (comp #(assoc % :url (:homepage %))
                                     #(select-keys % [:group-id :artifact-id :version
                                                      :description :scm :homepage])
                                     #(set/rename-keys % {:group_name :group-id
                                                          :jar_name   :artifact-id})))
                          (group-by (juxt :group-id :artifact-id))
                          (vals))]
    (for [jars grouped-jars]
      (let [jars (sort-by :version #(maven/compare-versions %2 %1) jars)]
        (-> (first jars)
            (dissoc :version)
            (assoc :versions (vec (distinct (map :version jars))))
            maven/without-nil-values)))))

(defn write-feed! [feed f]
  (with-open [w (-> (FileOutputStream. f)
                    (GZIPOutputStream.)
                    (PrintWriter.))]
    (binding [*out* w]
      (doseq [form feed]
        (prn form)))))

(defn -main [dest]
  (configure nil)
  (write-feed! (full-feed (:db config)) (str dest "/feed.clj.gz")))

