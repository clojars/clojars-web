(ns clojars.routes.repo
  (:require [clojars
             [auth :refer [require-authorization with-account]]
             [cloudfiles :as cf]
             [config :refer [config]]
             [db :as db]
             [errors :refer [report-error]]
             [file-utils :as fu]
             [maven :as maven]
             [search :as search]]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [cemerick.pomegranate.aether :as aether]
            [compojure
             [core :as compojure :refer [PUT defroutes]]
             [route :refer [not-found]]]
            [ring.util
             [codec :as codec]
             [response :as response]])
  (:import java.util.UUID
           org.apache.commons.io.FileUtils
           (java.io FileFilter IOException FileNotFoundException File)))

(defn versions [group-id artifact-id]
  (->> (.listFiles (io/file (config :repo) group-id artifact-id))
       (filter (memfn isDirectory))
       (sort-by (comp - (memfn last-modified)))
       (map (memfn getName))))

(defn save-to-file [dest input]
  (-> dest
      .getParentFile
      .mkdirs)
  (io/copy input dest)
  dest)

(defn- try-save-to-file [dest input]
  (try
    (save-to-file dest input)
    (catch IOException e
      (.delete dest)
      (throw e))))

(defn- pom? [file]
  (let [filename (if (string? file) file (.getName file))]
    (.endsWith filename ".pom")))

(defn find-upload-dir [{:keys [upload-dir]}]
  (let [dir (io/file upload-dir)]
    (if (and dir (.exists dir))
      dir
      (let [dir' (io/file (FileUtils/getTempDirectory)
                   (str "upload-" (UUID/randomUUID)))]
        (FileUtils/forceMkdir dir')
        dir'))))

(defn upload-request [db groupname session f]
  (with-account
    (fn [account]
      (let [upload-dir (find-upload-dir session)]
        (require-authorization db account groupname (partial f account upload-dir))
        ;; should we only do 201 if the file didn't already exist?
        {:status 201
         :headers {}
         :session (assoc session :upload-dir (.getAbsolutePath upload-dir))
         :body nil}))))

(defn find-pom [dir]
  (->> dir
    file-seq
    (filter pom?)
    first))

(defn- match-file-name [match f]
  (let [name (.getName f)]
    (if (string? match)
      (= match name)
      (re-find match name))))

(def metadata-edn "_metadata.edn")

(defn find-artifacts
  ([dir]
   (find-artifacts dir true))
  ([dir remove-checksums?]
   (let [tx (comp
              (filter (memfn isFile))
              (remove (partial match-file-name metadata-edn)))]
     (into []
       (if remove-checksums?
         (comp tx
           (remove (partial match-file-name #".sha1$"))
           (remove (partial match-file-name #".md5$")))
         tx)
       (file-seq dir)))))

(defn- throw-invalid
  ([message]
   (throw-invalid message nil))
  ([message meta]
   (throw-invalid message meta nil))
  ([message meta cause]
   (throw
     (ex-info message (merge {:report? false} meta) cause))))

(defn- validate-regex [x re message]
  (when-not (re-matches re x)
    (throw-invalid message {:value x
                            :regex re})))

(defn- validate-pom-entry [pom-data key value]
  (when-not (= (key pom-data) value)
    (throw-invalid
      (format "the %s in the pom (%s) does not match the %s you are deploying to (%s)"
        (name key) (key pom-data) (name key) value)
      {:pom pom-data})))

(defn- validate-pom [pom group name version]
  (validate-pom-entry pom :group group)
  (validate-pom-entry pom :name name)
  (validate-pom-entry pom :version version))

(defn snapshot-version? [version]
  (.endsWith version "-SNAPSHOT"))

(defn assert-non-redeploy [group-id artifact-id version]
  (when (and (not (snapshot-version? version))
          ;; TODO: have this check cloudfiles once that is canon
          (.exists (io/file (config :repo) (fu/group->path group-id) artifact-id version)))
    (throw-invalid "redeploying non-snapshots is not allowed (see http://git.io/vO2Tg)")))

(defn assert-jar-uploaded [artifacts pom]
  (when (and (= :jar (:packaging pom))
          (not (some (partial match-file-name #"\.jar$") artifacts)))
    (throw-invalid "no jar file was uploaded")))

(defn validate-checksums [artifacts]
  (doseq [f artifacts]
    ;; verify that at least one type of checksum file exists
    (when (not (or (.exists (fu/checksum-file f :md5))
                 (.exists (fu/checksum-file f :sha1))))
      (throw-invalid (format "no checksums provided for %s" (.getName f))
        {:file f}))
    ;; verify provided checksums are valid
    (doseq [type [:md5 :sha1]]
      (when (not (fu/valid-checksum-file? f type false))
        (throw-invalid (format "invalid %s checksum for %s" type (.getName f))
          {:file f})))))

(defn assert-signatures [artifacts]
  ;; if any signatures exist, require them for every artifact
  (let [asc-matcher (partial match-file-name #"\.asc$")]
    (when (some asc-matcher artifacts)
      (doseq [f artifacts
              :when (not (asc-matcher f))
              :when (not (.exists (io/file (str (.getAbsolutePath f) ".asc"))))]
        (throw-invalid (format "%s has no signature" (.getName f)) {:file f})))))

(defn validate-gav [group name version]
    ;; We're on purpose *at least* as restrictive as the recommendations on
    ;; https://maven.apache.org/guides/mini/guide-naming-conventions.html
    ;; If you want loosen these please include in your proposal the
    ;; ramifications on usability, security and compatiblity with filesystems,
    ;; OSes, URLs and tools.
    (validate-regex name #"^[a-z0-9_.-]+$"
      (str "project names must consist solely of lowercase "
        "letters, numbers, hyphens and underscores (see http://git.io/vO2Uy)"))
    
    (validate-regex group #"^[a-z0-9_.-]+$"
      (str "group names must consist solely of lowercase "
        "letters, numbers, hyphens and underscores (see http://git.io/vO2Uy)"))
    
    ;; Maven's pretty accepting of version numbers, but so far in 2.5 years
    ;; bar one broken non-ascii exception only these characters have been used.
    ;; Even if we manage to support obscure characters some filesystems do not
    ;; and some tools fail to escape URLs properly.  So to keep things nice and
    ;; compatible for everyone let's lock it down.
    (validate-regex version #"^[a-zA-Z0-9_.+-]+$"
      (str "version strings must consist solely of letters, "
        "numbers, dots, pluses, hyphens and underscores (see http://git.io/vO2TO)")))

(defn validate-deploy [dir pom {:keys [group name version]}]
  (try
    (validate-gav group name version)
    (validate-pom pom group name version)
    (assert-non-redeploy group name version)

    (let [artifacts (find-artifacts dir)]
      (assert-jar-uploaded artifacts pom)
      (validate-checksums artifacts)
      (assert-signatures (remove (partial match-file-name "maven-metadata.xml") artifacts)))
    
    (catch Exception e
      (throw (ex-info (.getMessage e)
               (merge
                 {:status 403
                  :status-message (str "Forbidden - " (.getMessage e))
                  :group group
                  :name name
                  :version version}
                 (ex-data e))
               (.getCause e))))))

(defn upload-to-cloudfiles [cloudfiles reporter from-dir file]
  (let [path (fu/subpath (.getAbsolutePath from-dir) (.getAbsolutePath file))]
    (try
      (cf/put-file cloudfiles path file)
      (catch Exception e
        ;; catch and report anything that fails for now
        ;; instead of letting it bubble up, since cloudfiles
        ;; isn't yet the primary repo
        (report-error reporter e {:path path :file file})))))
        
(defn finalize-deploy [cloudfiles db reporter search account repo ^File dir]
  (if-let [pom-file (find-pom dir)]
    (let [pom (try
                (maven/pom-to-map pom-file)
                (catch Exception e
                  (throw-invalid (str "invalid pom file: " (.getMessage e))
                    {:file pom-file}
                    e)))
          {:keys [group group-path name] :as posted-metadata} (read-string (slurp (io/file dir metadata-edn)))]

      ;; since we trigger on maven-metadata.xml, we don't actually
      ;; have the sums for it because they are uploaded *after* the
      ;; metadata file itself. This means that it's possible for a
      ;; corrupted file to slip through, so we try to parse it
      (let [md-file (io/file dir group-path name "maven-metadata.xml")]
        (try
          (maven/read-metadata md-file)
          (catch Exception e
            (throw-invalid "Failed to parse maven-metadata.xml"
              {:file md-file}
              e)))

        ;; If that succeeds, we create checksums for it
        (fu/create-checksum-file md-file :md5)
        (fu/create-checksum-file md-file :sha1))

      (validate-deploy dir pom posted-metadata)
      (db/check-and-add-group db account group)
      (FileUtils/copyDirectory dir (io/file repo)
        (reify FileFilter
          (accept [_ f]
            (not= metadata-edn (.getName f)))))
      (run! (partial upload-to-cloudfiles cloudfiles reporter dir) (find-artifacts dir false))

      (db/add-jar db account pom)
      (search/index! search (assoc pom
                              :at (.lastModified pom-file)))
      (spit (io/file dir ".finalized") ""))
    (throw-invalid "no pom file was uploaded")))

(defn- deploy-finalized? [dir]
  (.exists (io/file dir ".finalized")))

(defn- deploy-post-finalized-file [cloudfiles reporter repo tmp-repo file]
  (io/copy file (io/file repo (fu/subpath (.getAbsolutePath tmp-repo) (.getAbsolutePath file))))
  (upload-to-cloudfiles cloudfiles reporter tmp-repo file))

(defn- handle-versioned-upload [cloudfiles db reporter repo body session group artifact version filename]
  (let [groupname (fu/path->group group)]
    (upload-request
      db
      groupname
      session
      (fn [_ upload-dir]
        (spit (io/file upload-dir metadata-edn)
          (pr-str {:group groupname
                   :group-path group
                   :name  artifact
                   :version version}))
        (let [file (try-save-to-file (io/file upload-dir group artifact version filename) body)]
          (when (deploy-finalized? upload-dir)
            (deploy-post-finalized-file cloudfiles reporter repo upload-dir file)))))))

;; web handlers
(defn routes [cloudfiles db reporter search]
  (compojure/routes
   (PUT ["/:group/:artifact/:file"
         :group #".+" :artifact #"[^/]+" :file #"maven-metadata\.xml[^/]*"]
        {body :body session :session {:keys [group artifact file]} :params}
        (if (snapshot-version? artifact)
          ;; SNAPSHOT metadata will hit this route, but should be
          ;; treated as a versioned file upload.
          ;; See: https://github.com/clojars/clojars-web/issues/319
          (let [version artifact
                group-parts (string/split group #"/")
                group (string/join "/" (butlast group-parts))
                artifact (last group-parts)]
            (handle-versioned-upload cloudfiles db reporter (config :repo) body session group artifact version file))
          (if (re-find #"maven-metadata\.xml$" file)
            ;; ignore metadata sums, since we'll recreate those when
            ;; the deploy is finalizied
            (let [groupname (fu/path->group group)]
              (upload-request
                db
                groupname
                session
                (fn [account upload-dir]
                  (let [file (io/file upload-dir group artifact file)]
                    (try-save-to-file file body)
                    (finalize-deploy cloudfiles db reporter search
                      account (config :repo) upload-dir)))))
            {:status 201
             :headers {}
             :body nil})))
   (PUT ["/:group/:artifact/:version/:filename"
         :group #"[^\.]+" :artifact #"[^/]+" :version #"[^/]+"
         :filename #"[^/]+(\.pom|\.jar|\.sha1|\.md5|\.asc)$"]
        {body :body session :session {:keys [group artifact version filename]} :params}
        (handle-versioned-upload cloudfiles db reporter (config :repo) body session group artifact version filename))
   (PUT "*" _ {:status 400 :headers {}})
   (not-found "Page not found")))

(defn wrap-file [app dir]
  (fn [req]
    (if-not (= :get (:request-method req))
      (app req)
      (let [path (codec/url-decode (:path-info req))]
        (or (response/file-response path {:root dir})
            (app req))))))

(defn wrap-reject-double-dot [f]
  (fn [req]
    (if (re-find #"\.\." (:uri req))
      {:status 400 :headers {}}
      (f req))))

(defn wrap-exceptions [app reporter]
  (fn [req]
    (try
      (app req)
      (catch Exception e
        (report-error reporter e)
        (let [data (ex-data e)]
          {:status (or (:status data) 403)
           :headers {"status-message" (:status-message data)}
           :body (.getMessage e)})))))
