(ns clojars.repo
  (:require [clojars.auth :refer [with-account authorized!]]
            [clojars.db :refer [find-jar add-jar update-jar]]
            [clojars.config :refer [config]]
            [clojars.maven :as maven]
            [compojure.core :refer [defroutes PUT ANY]]
            [clojure.java.io :as io]
            [clojure.string :as string])
  (:import java.io.StringReader))

(defn save-to-file [sent-file body]
  (-> sent-file
      .getParentFile
      .mkdirs)
  (with-open [wrtr (io/writer sent-file)]
    (.write wrtr body)))

(defroutes routes
  (PUT ["/:group/:artifact/:file"
        :group #".+" :artifact #"[^/]+" :file #"maven-metadata\.xml[^/]*"]
       {body :body {:keys [group artifact file]} :params}
       (with-account
         (authorized!
          (string/replace group "/" ".")
          (save-to-file (io/file (config :repo) group artifact file)
                        (slurp body))
          {:status 201 :headers {} :body nil})))
  (PUT ["/:group/:artifact/:version/:file"
        :group #".+" :artifact #"[^/]+" :version #"[^/]+" :file #"[^/]+"]
       {body :body {:keys [group artifact version file]} :params}
       (let [groupname (string/replace group "/" ".")]
         (with-account
           (authorized!
            (string/replace group "/" ".")
            (let [contents (slurp body)]
              (if (.endsWith file ".pom")
                (if (find-jar groupname artifact version)
                  (update-jar account
                              (merge
                               (maven/pom-to-map (StringReader. contents))
                               {:group groupname
                                :name  artifact
                                :version version}))
                  (add-jar account (merge
                                    (maven/pom-to-map (StringReader. contents))
                                    {:group groupname
                                     :name  artifact
                                     :version version})))
                (when-not (find-jar groupname artifact version)
                  (add-jar account  {:group groupname
                                     :name  artifact
                                     :version version})))
              (save-to-file (io/file (config :repo) group artifact version file)
                            contents))
            {:status 201 :headers {} :body nil})))))