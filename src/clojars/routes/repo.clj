(ns clojars.routes.repo
  (:require
   [clojars.auth :as auth :refer [with-account]]
   [clojars.db :as db]
   [clojars.errors :refer [report-error]]
   [clojars.event :as event]
   [clojars.file-utils :as fu]
   [clojars.gradle :as gradle]
   [clojars.log :as log]
   [clojars.maven :as maven]
   [clojars.search :as search]
   [clojars.storage :as storage]
   [clojars.util :as util]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [compojure.core :as compojure :refer [PUT]]
   [compojure.route :refer [not-found]]
   [ring.util.codec :as codec]
   [ring.util.response :as response])
  (:import
   (java.io
    File
    IOException)
   (java.util
    UUID)
   org.apache.commons.io.FileUtils))

(set! *warn-on-reflection* true)

(defn save-to-file
  [^File dest input]
  (-> dest
      .getParentFile
      .mkdirs)
  (io/copy input dest)
  dest)

(defn- try-save-to-file
  [^File dest input]
  (try
    (save-to-file dest input)
    (catch IOException e
      (.delete dest)
      (throw e))))

(defn- pom?
  [^File file]
  (let [^String filename (if (string? file) file (.getName file))]
    (.endsWith filename ".pom")))

(defn- module?
  [^File file]
  (let [^String filename (if (string? file) file (.getName file))]
    (.endsWith filename ".module")))

(def metadata-edn "_metadata.edn")

(defn read-metadata [dir]
  (let [md-file (io/file dir metadata-edn)]
    (when (.exists md-file)
      (read-string (slurp md-file)))))

(defn- token-from-session
  [{:as _session :cemerick.friend/keys [identity]}]
  (let [{:keys [authentications current]} identity]
    (get-in authentications [current :token])))

(defn- write-metadata
  [session dir group-name group-path artifact version timestamp-version]
  (let [token-id (:id (token-from-session session))
        metadata (util/assoc-some
                  (read-metadata dir)
                  :group group-name
                  :group-path group-path
                  :name artifact
                  :version version
                  :timestamp-version timestamp-version
                  :token-id token-id)]
    (spit (io/file dir metadata-edn) (pr-str metadata))))

(defn find-upload-dir
  ^File [group artifact version timestamp-version {:keys [upload-dirs]}]
  (if-let [dir (some (fn [dir]
                       (let [dir (io/file dir)
                             metadata (read-metadata dir)
                             if= #(if (and %1 %2) (= %1 %2) true)]
                         (when (and dir (.exists dir)
                                    (= [group artifact] ((juxt :group :name) metadata))
                                    (if= (:version metadata) version)
                                    (if= (:timestamp-version metadata) timestamp-version))
                           dir)))
                     ;; We reverse sort the upload dirs to get the newer dirs
                     ;; first (the dir name includes the creation time in
                     ;; millis). This is a specific fix for #849 to allow
                     ;; multiple deploys of different versions in the same
                     ;; session to succeed.
                     ;;
                     ;; They would fail occasionally depending on the natural
                     ;; sort of the upload-dirs. When we are finalizing a
                     ;; deploy, we don't have the version, since the finalize is
                     ;; triggered by the maven-metadata.xml upload, which isn't
                     ;; versioned. This means we use whatever dir for the
                     ;; group+artifact that we find first, which may not be the
                     ;; correct dir.
                     (sort #(compare %2 %1) upload-dirs))]
    dir
    (doto (io/file (FileUtils/getTempDirectory)
                   (format "upload-%s-%s" (System/currentTimeMillis) (UUID/randomUUID)))
      (FileUtils/forceMkdir))))

(def ^:private ^:dynamic *db*
  "Used to avoid passing the db to every fn that needs to audit."
  nil)

(defn- throw-invalid
  ([tag message]
   (throw-invalid tag message nil))
  ([tag message meta]
   (throw-invalid tag message meta nil))
  ([tag message meta cause]
   ;; don't log again if we threw this exception before
   (when-not (:throw-invalid? meta)
     (log/audit *db* {:tag tag
                      :message message})
     (log/info {:status :failed
                :message message}))
   (throw
    (ex-info message (merge {:report? false
                             :throw-invalid? true}
                            meta)
             cause))))

(defn- throw-forbidden
  [e-or-message meta]
  (let [throwable? (instance? Throwable e-or-message)
        [message cause] (if throwable?
                          [(.getMessage ^Throwable e-or-message)
                           (.getCause ^Throwable e-or-message)]
                          [e-or-message])]
    (when throwable?
      (log/error {:tag :upload-exception
                  :error e-or-message}))
    (throw-invalid
     :deploy-forbidden
     message
     (merge
      {:status 403
       :status-message (str "Forbidden - " message)}
      meta
      (ex-data e-or-message))
     cause)))

(defn- check-group+project [db account group artifact]
  (try
    (db/check-group+project db account group artifact)
    (catch Exception e
      (throw-forbidden e
                       {:account account
                        :group group}))))

(defn upload-request [db groupname artifact version timestamp-version session f]
  (with-account
    (fn [account]
      (log/with-context {:tag :upload
                         :group groupname
                         :artifact artifact
                         :version version
                         :timestamp-version timestamp-version
                         :username account}
        (when artifact
          ;; will throw if there are any issues
          (check-group+project db account groupname artifact))
        (let [upload-dir (find-upload-dir groupname artifact version timestamp-version session)]
          (f account upload-dir)
          ;; should we only do 201 if the file didn't already exist?
          (log/info {:status :success})
          {:status 201
           :headers {}
           :session (update session
                            :upload-dirs (fnil conj #{}) (.getAbsolutePath upload-dir))
           :body nil})))))

(defn find-pom
  ^File [dir]
  (util/filter-some pom? (file-seq dir)))

(defn find-module
  ^File [dir]
  (util/filter-some module? (file-seq dir)))

(defn- match-file-name
  [match ^File f]
  (let [name (.getName f)]
    (if (string? match)
      (= match name)
      (re-find match name))))

(defn find-artifacts
  ([dir]
   (find-artifacts dir true))
  ([dir remove-checksums?]
   (let [tx (comp
             (filter (fn [^File f] (.isFile f)))
             (remove (partial match-file-name metadata-edn)))]
     (into []
           (if remove-checksums?
             (comp tx
                   (remove (partial match-file-name #".sha1$"))
                   (remove (partial match-file-name #".md5$")))
             tx)
           (file-seq dir)))))

(defn- validate-regex [x re message]
  (when-not (re-matches re x)
    (throw-invalid :regex-validation-failed
                   message {:value x
                            :regex re})))

(defn- validate-pom-entry [pom-data key value]
  (when-not (= (key pom-data) value)
    (throw-invalid
     :pom-entry-mismatch
     (format "the %s in the pom (%s) does not match the %s you are deploying to (%s)"
             (name key) (key pom-data) (name key) value)
     {:pom pom-data})))

(defn- validate-pom-license
  [pom]
  (when (empty? (:licenses pom))
    (throw-invalid
     :missing-license
     "the POM file does not include a license. See https://bit.ly/3PQunZU")))

(defn- validate-pom [pom group name version]
  (validate-pom-entry pom :group group)
  (validate-pom-entry pom :name name)
  (validate-pom-entry pom :version version)
  (validate-pom-license pom))

(defn- validate-module-entry
  "Validates a key in a Gradle module"
  [module ks expected]
  (let [actual (get-in module ks)]
    (when-not (= actual expected)
      (throw-invalid
       :module-entry-mismatch
       (format "the %s in the gradle module (%s) does not match the coordinate you are deploying to (%s)"
               (str/join " " (map name ks)) actual expected)))))

(defn- validate-module [module group name version]
  (validate-module-entry module [:component :group] group)
  (validate-module-entry module [:component :module] name)
  (validate-module-entry module [:component :version] version))

(defn assert-non-redeploy [db group-id artifact-id version]
  (when (and (not (maven/snapshot-version? version))
             (db/find-jar db group-id artifact-id version))
    (throw-invalid :non-snapshot-redeploy
                   "redeploying non-snapshots is not allowed. See https://bit.ly/3EYzhwT")))

(defn assert-non-central-shadow [group-id artifact-id]
  (when-not (maven/can-shadow-maven? group-id artifact-id)
    (when-let [ret (maven/exists-on-central?* group-id artifact-id)]
      (let [meta {:group-id group-id
                  :artifact-id artifact-id
                  ;; report both failures to reach central and shadow attempts to sentry
                  :report? true}]
        (if (= :failure ret)
          (throw-invalid :central-shadow-check-failure
                         "failed to contact Maven Central to verify project name. See https://bit.ly/3rTLqxZ"
                         (assoc meta :status 503))
          (throw-invalid :central-shadow
                         "shadowing Maven Central artifacts is not allowed. See https://bit.ly/3rTLqxZ"
                         meta))))))

(defn validate-checksums [artifacts]
  (doseq [^File f artifacts]
    ;; verify that at least one type of checksum file exists
    (when (and (not (or (.exists (fu/checksum-file f :md5))
                        (.exists (fu/checksum-file f :sha1))))
               ;; Aether by default no longer checksums signature files, so we don't
               ;; throw if it is missing
               (not (match-file-name #"\.asc$" f))
               ;; Same for SSH signed files
               (not (match-file-name #"\.sig$" f)))
      (throw-invalid :file-missing-checksum
                     (format "no checksums provided for %s" (.getName f))
                     {:file f}))
    ;; verify provided checksums are valid
    (doseq [type [:md5 :sha1]]
      (when (not (fu/valid-checksum-file? f type false))
        (throw-invalid :file-invalid-checksum
                       (format "invalid %s checksum for %s" type (.getName f))
                       {:file f})))))

(defn- assert-signatures [suffix artifacts]
  ;; if any signatures exist, require them for every artifact
  (let [suffix-matcher (partial match-file-name (re-pattern (format "\\%s$" suffix)))]
    (when (some suffix-matcher artifacts)
      (doseq [^File f artifacts
              :when (not (suffix-matcher f))
              :when (not (.exists (io/file (str (.getAbsolutePath f) suffix))))]
        (throw-invalid :file-missing-signature
                       (format "%s has no signature" (.getName f)) {:file f})))))

(defn- validate-jar-name+version
  [name version]
  ;; We're on purpose *at least* as restrictive as the recommendations on
  ;; https://maven.apache.org/guides/mini/guide-naming-conventions.html
  ;; If you want loosen these please include in your proposal the
  ;; ramifications on usability, security and compatibility with filesystems,
  ;; OSes, URLs and tools.
  (validate-regex name maven/group+jar-name-regex
                  (str "project names must consist solely of lowercase "
                       "letters, numbers, hyphens and underscores. See https://bit.ly/3MuL20A"))

  ;; Maven's pretty accepting of version numbers, but so far in 2.5 years
  ;; bar one broken non-ascii exception only these characters have been used.
  ;; Even if we manage to support obscure characters some filesystems do not
  ;; and some tools fail to escape URLs properly.  So to keep things nice and
  ;; compatible for everyone let's lock it down.
  (validate-regex version maven/version-regex
                  (str "version strings must consist solely of letters, "
                       "numbers, dots, pluses, hyphens and underscores. See https://bit.ly/3Kf5KzX")))

(defn validate-deploy [db dir pom module {:keys [group name version]}]
  (validate-jar-name+version name version)
  (when module
    (validate-module module group name version))
  (validate-pom pom group name version)
  (assert-non-redeploy db group name version)
  (assert-non-central-shadow group name)

  (let [artifacts (find-artifacts dir)]
    (validate-checksums artifacts)
    (assert-signatures ".asc" (remove (partial match-file-name "maven-metadata.xml") artifacts))
    (assert-signatures ".sig" (remove (partial match-file-name "maven-metadata.xml") artifacts))))

(defmacro profile [meta & body]
  `(let [start# (System/currentTimeMillis)]
     ~@body
     (prn (assoc ~meta :time (- (System/currentTimeMillis) start#)))))

(defn- maybe-consume-single-use-token
  [db session]
  (let [token (token-from-session session)]
    (when (= "yes" (:single_use token))
      (db/consume-deploy-token db (:id token)))))

(defn- gen-repo-paths
  [{:as _version-data :keys [group-path name version]}]
  (let [parts (conj (str/split group-path #"/")
                    name version)]
    (reduce
     (fn [acc part]
       (conj acc
             (if (str/blank? (peek acc))
               part
               (format "%s/%s" (peek acc) part))))
     [""]
     parts)))

(defn- emit-deploy-events
  [db event-emitter {:as version-data :keys [group]}]
  (doseq [path (gen-repo-paths version-data)]
    (event/emit event-emitter :repo-path-needs-index {:path path}))
  (doseq [user (db/group-active-users db group)]
    (event/emit event-emitter :version-deployed (assoc version-data :user user))))

(defn- finalize-deploy
  [storage db event-emitter search session account ^File dir]
  (if-let [pom-file (find-pom dir)]
    (let [pom (try
                (maven/pom-to-map pom-file)
                (catch Exception e
                  (throw-invalid :invalid-pom-file
                                 (str "invalid pom file: " (.getMessage e))
                                 {:file pom-file}
                                 e)))

          module-file (find-module dir)
          module (try
                   (when module-file
                     (gradle/module-to-map module-file))
                   (catch Exception e
                     (throw-invalid :invalid-module-file
                                    (str "invalid gradle module file: " (.getMessage e))
                                    {:file module-file})))

          {:keys [group group-path name version] :as posted-metadata}
          (read-metadata dir)

          md-file (io/file dir group-path name "maven-metadata.xml")]
      (log/with-context {:version version}
        ;; since we trigger on maven-metadata.xml, we don't actually
        ;; have the sums for it because they are uploaded *after* the
        ;; metadata file itself. This means that it's possible for a
        ;; corrupted file to slip through, so we try to parse it
        (try
          (maven/read-metadata md-file)
          (catch Exception e
            (throw-invalid :invalid-maven-metadata-file
                           "Failed to parse maven-metadata.xml"
                           {:file md-file}
                           e)))

        ;; If that succeeds, we create checksums for it
        (fu/create-checksum-file md-file :md5)
        (fu/create-checksum-file md-file :sha1)

        (validate-deploy db dir pom module posted-metadata)
        (run! #(storage/write-artifact
                storage
                (fu/subpath (.getAbsolutePath dir) (.getAbsolutePath ^File %)) %)
              (->> (file-seq dir)
                   (remove (fn [^File f] (.isDirectory f)))
                   (remove #(some #{(.getName ^File %)} [metadata-edn]))))

        (db/add-jar db account pom)
        (maybe-consume-single-use-token db session)
        (log/audit db {:tag :deployed})
        (log/info {:tag :deploy-finalized})
        (future
          (search/index! search (db/find-jar db group name version))
          (log/info {:tag :deploy-indexed}))
        (spit (io/file dir ".finalized") "")
        (emit-deploy-events db event-emitter (assoc posted-metadata :deployer-username account))))
    (throw-invalid :missing-pom-file "no pom file was uploaded")))

(defn- deploy-finalized? [dir]
  (.exists (io/file dir ".finalized")))

(defn- deploy-post-finalized-file [storage ^File tmp-repo ^File file]
  (storage/write-artifact storage
                          (fu/subpath (.getAbsolutePath tmp-repo) (.getAbsolutePath file)) file))

(defn- token-session-matches-group-artifact?
  [session group artifact]
  (let [{:as token :keys [group_name jar_name]} (token-from-session session)]
    (or
     ;; not a token request
     (nil? token)

     ;; token has no scope
     (and (nil? group_name)
          (nil? jar_name))

     ;; token is scoped to this group/artifact
     (and (= group group_name)
          (= artifact jar_name))

     ;; token is only group scoped and matches
     (and (nil? jar_name)
          (= group group_name)))))

(defn- maybe-assert-token-matches-group+artifact
  [session group artifact]
  (when-not (token-session-matches-group-artifact? session group artifact)
    (throw-forbidden
     "The provided token's scope doesn't allow deploying this artifact. See https://bit.ly/3LmCclv"
     {:group group
      :artifact artifact})))

(defn- maybe-assert-single-use-token-unused
  [session upload-dir]
  (let [token (token-from-session session)]
    ;; We are starting a new upload with a used token. We can't blindly reject
    ;; all used token requests since we may get legitimate upload requests after
    ;; the deploy is finalized
    (when (and (= "used" (:single_use token))
               (nil? (:token-id (read-metadata upload-dir))))
      (throw-forbidden
       "The provided single-use token has already been used"
       {}))))

(defn- maybe-assert-user-has-mfa-enabled
  [db account groupname]
  (let [group-settings (db/get-group-settings db groupname)]
    (when (and (:require_mfa_to_deploy group-settings)
               (not (db/user-has-mfa? db account)))
      (throw-forbidden
       (format "The group '%s' requires you to have two-factor auth enabled to deploy. See https://bit.ly/45qrtA8"
               groupname)
       {:group groupname
        :username account}))))

(defn- handle-versioned-upload [storage db body session group artifact version filename]
  (let [groupname (fu/path->group group)
        timestamp-version (when (maven/snapshot-version? version) (maven/snapshot-timestamp-version filename))]
    (upload-request
     db
     groupname
     artifact
     version
     timestamp-version
     session
     (fn [account upload-dir]
       (maybe-assert-token-matches-group+artifact session groupname artifact)
       (maybe-assert-single-use-token-unused session upload-dir)
       (maybe-assert-user-has-mfa-enabled db account groupname)
       (write-metadata session upload-dir groupname group artifact version timestamp-version)
       (let [file (try-save-to-file (io/file upload-dir group artifact version filename) body)]
         (when (deploy-finalized? upload-dir)
           ;; a deploy should never get this far with a bad group,
           ;; but since this includes the group authorization check,
           ;; we do it here just in case. Will throw if there are any
           ;; issues.
           (check-group+project db account groupname artifact)
           (deploy-post-finalized-file storage upload-dir file)))))))

;; web handlers
(defn routes [storage db event-emitter search]
  (compojure/routes
   (PUT ["/:group/:artifact/:file"
         :group #".+" :artifact #"[^/]+" :file #"maven-metadata\.xml[^/]*"]
        {body :body session :session {:keys [group artifact file]} :params}
        (binding [*db* db]
          (if (maven/snapshot-version? artifact)
            ;; SNAPSHOT metadata will hit this route, but should be
            ;; treated as a versioned file upload.
            ;; See: https://github.com/clojars/clojars-web/issues/319
            (let [version artifact
                  group-parts (str/split group #"/")
                  group (str/join "/" (butlast group-parts))
                  artifact (last group-parts)]
              (handle-versioned-upload storage db body session group artifact version file))
            (if (re-find #"maven-metadata\.xml$" file)
              ;; ignore metadata sums, since we'll recreate those when
              ;; the deploy is finalizied
              (let [groupname (fu/path->group group)]
                (upload-request
                 db
                 groupname
                 artifact
                 nil
                 nil
                 session
                 (fn [account upload-dir]
                   (let [file (io/file upload-dir group artifact file)
                         existing-sum (when (.exists file) (fu/checksum file :sha1))]
                     (try-save-to-file file body)
                     ;; only finalize if we haven't already or the
                     ;; maven-metadata.xml file doesn't match the one
                     ;; we already have
                     ;; https://github.com/clojars/clojars-web/issues/640
                     (when-not (or (deploy-finalized? upload-dir)
                                   (= (fu/checksum file :sha1) existing-sum))
                       (try
                         (finalize-deploy storage db event-emitter search
                                          session account upload-dir)
                         (catch Exception e
                           (throw-forbidden
                            e
                            (merge
                             {:account account
                              :group group
                              :artifact artifact
                              ;; This will cause exceptions that /aren't/ from a
                              ;; throw-* call to be reported to sentry, etc
                              ;; since the data for those will have :report?
                              ;; false
                              :report? true}
                             (ex-data e))))))))))
              {:status 201
               :headers {}
               :body nil}))))
   (PUT ["/:group/:artifact/:version/:filename"
         :group #"[^\.]+" :artifact #"[^/]+" :version #"[^/]+"
         :filename #"[^/]+(\.pom|\.jar|\.sha1|\.md5|\.asc|\.module|\.sig)$"]
        {body :body session :session {:keys [group artifact version filename]} :params}
        (binding [*db* db]
          (handle-versioned-upload storage db body session group artifact version filename)))
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

(defn wrap-reject-non-token [f db]
  (fn [req]
    (if (auth/unauthed-or-token-request? req)
      (f req)
      (let [{:keys [username]} (auth/parse-authorization-header (get-in req [:headers "authorization"]))
            message "a deploy token is required to deploy. See https://bit.ly/3LmCclv"]
        (log/audit db {:tag :deploy-password-rejection
                       :message message
                       :username username})
        (log/info {:tag :deploy-password-rejection
                   :username username})
        {:status 401
         :status-message (format "Unauthorized - %s" message)}))))

(defn wrap-exceptions [app reporter]
  (fn [req]
    (let [request-id (log/trace-id)]
      (try
        (log/with-context {:trace-id request-id}
          (app req))
        (catch Exception e
          (report-error reporter e nil request-id)
          (let [data (ex-data e)]
            {:status (or (:status data) 403)
             :status-message (:status-message data)
             :body (.getMessage e)}))))))
