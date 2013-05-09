(ns clojars.routes.repo
  (:require [clojars.auth :refer [with-account require-authorization]]
            [clojars.db :refer [add-jar]]
            [clojars.config :refer [config]]
            [clojars.maven :as maven]
            [clojars.event :as ev]
            [compojure.core :refer [defroutes PUT ANY]]
            [compojure.route :refer [not-found]]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [ring.util.codec :as codec]
            [ring.util.response :as response]
            [clj-stacktrace.repl :refer [pst]])
  (:import java.io.StringReader))

(defn versions [group-id artifact-id]
  (->> (.listFiles (io/file (config :repo) group-id artifact-id))
       (filter (memfn isDirectory))
       (sort-by (comp - (memfn last-modified)))
       (map (memfn getName))))

(defn find-jar
  ([group-id artifact-id]
     (find-jar group-id artifact-id (first (versions group-id artifact-id))))
  ([group-id artifact-id version]
     (try
       (maven/pom-to-map (io/file (config :repo) group-id artifact-id version
                                  (format "%s-%s.pom" artifact-id version)))
       (catch java.io.FileNotFoundException e
         nil))))

(defn group-artifacts [group-id]
  (.list (io/file (config :repo) group-id)))

(defn user-artifacts [username]
  (mapcat group-artifacts (:groups (@ev/users username))))

;; web handlers

(defn save-to-file [sent-file input]
  (-> sent-file
      .getParentFile
      .mkdirs)
  (io/copy input sent-file))

(defroutes routes
  (PUT ["/:group/:artifact/:file"
        :group #".+" :artifact #"[^/]+" :file #"maven-metadata\.xml[^/]*"]
       {body :body {:keys [group artifact file]} :params}
       (with-account
         (require-authorization
          (string/replace group "/" ".")
          (save-to-file (io/file (config :repo) group artifact file)
                        body)
          ;; should we only do 201 if the file didn't already exist?
          {:status 201 :headers {} :body nil})))
  (PUT ["/:group/:artifact/:version/:filename"
        :group #"[^\.]+" :artifact #"[^/]+" :version #"[^/]+"
        :filename #"[^/]+(\.pom|\.jar|\.sha1|\.md5|\.asc)$"]
       {body :body {:keys [group artifact version filename]} :params}
       (let [groupname (string/replace group "/" ".")]
         (with-account
           (require-authorization
            groupname
            (try
              (let [info {:group groupname
                          :name  artifact
                          :version version}
                    file (io/file (config :repo) group
                                  artifact version filename)]
                ;;TODO once db is removed this whole if block
                ;;can be reduced to the common
                ;;(try (save-to-file...) (catch ...))
                ;; Be consistent with scp only recording pom or jar
                (when (some #(.endsWith filename %) [".pom" ".jar"])
                  (ev/record-deploy {:group-id groupname
                                     :artifact-id artifact
                                     :version version} account file))
                (if (.endsWith filename ".pom")
                  (let [contents (slurp body)
                        pom-info (merge (maven/pom-to-map
                                         (StringReader. contents)) info)]
                    (add-jar account pom-info)
                    (try
                      (save-to-file file contents)
                      (catch java.io.IOException e
                        (.delete file)
                        (throw e))))
                  (do
                    (add-jar account info)
                    (try
                      (save-to-file file body)
                      (catch java.io.IOException e
                        (.delete file)
                        (throw e))))))
              {:status 201 :headers {} :body nil}
              (catch Exception e
                (pst e)
                {:status 403 :headers {} :body (.getMessage e)}))))))
  (PUT "*" _ {:status 400 :headers {}})
  (not-found "Page not found"))

(defn wrap-file [app dir]
  (fn [req]
    (if-not (= :get (:request-method req))
      (app req)
      (let [path (codec/url-decode (:path-info req))]
        (or (response/file-response path {:root dir})
            (app req))))))
