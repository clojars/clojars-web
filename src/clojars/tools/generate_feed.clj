(ns clojars.tools.generate-feed
  (:require [clojure.java.io :as io]
            [clojars.maven :as maven]
            [clojure.set :as set])
  (:import java.util.zip.GZIPOutputStream
           (java.io FileOutputStream PrintWriter))
  (:gen-class))

(defn pom-seq [repo]
  (for [f (file-seq repo)
        :when (and (not (re-matches #".*/\..*" (str f)))
                   (.endsWith (.getName f) ".pom"))
        :let [pom (try
                    (-> (maven/pom-to-map f)
                        (update :scm maven/scm-to-map)
                        maven/without-nil-values)
                    (catch Exception e (.printStackTrace e)))]
        :when pom]
    pom))

(defn full-feed [repo]
  (let [grouped-jars (->> (pom-seq repo)
                          (map (comp #(select-keys % [:group-id :artifact-id :version
                                                      :description :scm :url
                                                      :homepage])
                                     #(set/rename-keys % {:group :group-id
                                                          :name  :artifact-id})))
                          (group-by (juxt :group-id :artifact-id))
                          (vals))]
    (for [jars grouped-jars]
      (let [jars (sort-by :version #(maven/compare-versions %2 %1) jars)]
        (-> (first jars)
            (dissoc :version)
            (assoc :versions (vec (distinct (map :version jars)))))))))

(defn write-feed! [feed f]
  (with-open [w (-> (FileOutputStream. f)
                    (GZIPOutputStream.)
                    (PrintWriter.))]
    (binding [*out* w]
      (doseq [form feed]
        (prn form)))))

(defn -main [repo dest]
  (write-feed! (full-feed (io/file repo)) (str dest "/feed.clj.gz")))

