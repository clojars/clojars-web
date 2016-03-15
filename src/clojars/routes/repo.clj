(ns clojars.routes.repo
  (:require [clojars
             [auth :refer [require-authorization with-account]]
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
  (:import java.io.StringReader
           java.util.UUID
           org.apache.commons.io.FileUtils))

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

(defn save-to-file [sent-file input]
  (-> sent-file
      .getParentFile
      .mkdirs)
  (io/copy input sent-file))

(defn- try-save-to-file [sent-file input]
  (try
    (save-to-file sent-file input)
    (catch java.io.IOException e
      (.delete sent-file)
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

(defn re-find-after [re after v]
  (re-find re (subs v (+ (.indexOf v after) (count after)))))

;; borrowed from
;; https://github.com/technomancy/leiningen/tree/2.5.3/src/leiningen/deploy.clj#L137
;; and modified
(defn- extension [f]
  (let [name (.getName f)]
    (if-let [[_ signed-extension] (re-find #"\.([a-z]+\.asc)$" name)]
      signed-extension
      (last (.split name "\\.")))))

(defn- classifier [version f]
  (when-let [[_ classifier] (re-find-after #"^-(.*?)\.jar" version (.getName f))]
    classifier))

(defn- match-file-name [re f]
  (re-find re (.getName f)))

(defn find-artifacts [dir]
  (into []
    (comp
      (filter (memfn isFile))
      (remove (partial match-file-name #".sha1$"))
      (remove (partial match-file-name #".md5$"))
      (remove (partial match-file-name #"^maven-metadata\.xml"))
      (remove (partial match-file-name #"^metadata\.edn$")))
    (file-seq dir)))

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
         (.exists (io/file (config :repo) (string/replace group-id "." "/")
                    artifact-id version)))
   (throw-invalid "redeploying non-snapshots is not allowed (see http://git.io/vO2Tg)")))

(defn assert-jar-uploaded [artifacts pom]
  (when (and (= :jar (:packaging pom))
          (not (some (partial match-file-name #"\.jar$") artifacts)))
    (throw-invalid "no jar file was uploaded")))

(defn validate-checksums [artifacts]
  (doseq [f artifacts]
    ;; verify that at least one type of checksum file exists
    (when (not (or (.exists (fu/sum-file f :md5))
                 (.exists (fu/sum-file f :sha1))))
      (throw-invalid (str "no checksum provided for " (.getName f) {:file f})))
    ;; verify provided checksums are valid
    (when (not (fu/valid-sums? f false))
      (throw-invalid (str "invalid checksum for " (.getName f) {:file f})))))

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
      (assert-signatures artifacts))
    
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

(defn finalize-deploy [db search account repo dir]
  (try
    (if-let [pom-file (find-pom dir)]
      (let [pom (try
                  (maven/pom-to-map pom-file)
                  (catch Exception e
                    (throw-invalid (str "invalid pom file: " (.getMessage e))
                      {:file pom-file}
                      e)))
            {:keys [group name version] :as posted-metadata} (read-string (slurp (io/file dir "metadata.edn")))
            [_ version-from-pom-name] (re-find-after #"^-(.*)\.pom$" name (.getName pom-file))]
        (validate-deploy dir pom posted-metadata)
        (db/check-and-add-group db account group)
        (aether/deploy
          :coordinates [(symbol group name) version]
          :artifact-map (reduce #(assoc %1
                                   ;; use the version from the pom
                                   ;; name so we have the timestamped
                                   ;; snapshot version
                                   [:classifier (classifier version-from-pom-name %2)
                                    :extension (extension %2)]
                                   %2)
                          {} (find-artifacts dir))
          :repository {"local" {:url (-> repo io/file .toURI .toURL)}})
        (db/add-jar db account pom)
        (search/index! search (assoc pom
                                :at (.lastModified pom-file))))
      (throw-invalid "no pom file was uploaded"))
    (finally
      (FileUtils/deleteQuietly dir))))

(defn- handle-versioned-upload [db body session group artifact version filename]
  (let [groupname (string/replace group "/" ".")]
    (upload-request
      db
      groupname
      session
      (fn [_ upload-dir]
        (spit (io/file upload-dir "metadata.edn")
          (pr-str {:group groupname
                   :name  artifact
                   :version version}))
        (try-save-to-file (io/file upload-dir group artifact version filename) body)))))

;; web handlers
(defn routes [db search]
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
            (handle-versioned-upload db body session group artifact version file))
          (if (re-find #"maven-metadata\.xml$" file)
            ;; ignore metadata sums, since we'll recreate those when
            ;; the deploy is finalizied
            (let [groupname (string/replace group "/" ".")]
              (upload-request
                db
                groupname
                session
                (fn [account upload-dir]
                  (let [file (io/file upload-dir group artifact file)]
                    (try-save-to-file file body)
                    (finalize-deploy db search account (config :repo) upload-dir)))))
            {:status 201
             :headers {}
             :body nil})))
   (PUT ["/:group/:artifact/:version/:filename"
         :group #"[^\.]+" :artifact #"[^/]+" :version #"[^/]+"
         :filename #"[^/]+(\.pom|\.jar|\.sha1|\.md5|\.asc)$"]
        {body :body session :session {:keys [group artifact version filename]} :params}
        (handle-versioned-upload db body session group artifact version filename))
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
