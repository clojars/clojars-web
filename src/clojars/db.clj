(ns clojars.db
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clj-time.core :as time]
            [clj-time.coerce :as time.coerce]
            [clojars.config :refer [config]]
            [clojure.java.jdbc :as jdbc]
            [clojars.db.sql :as sql]
            [cemerick.friend.credentials :as creds])
  (:import java.security.MessageDigest
           java.util.Date
           java.security.SecureRandom
           java.io.File
           java.util.concurrent.Executors))

(def reserved-names
  #{"clojure" "clojars" "clojar" "register" "login"
    "pages" "logout" "password" "username" "user"
    "repo" "repos" "jar" "jars" "about" "help" "doc"
    "docs" "images" "js" "css" "maven" "api"
    "download" "create" "new" "upload" "contact" "terms"
    "group" "groups" "browse" "status" "blog" "search"
    "email" "welcome" "devel" "development" "test" "testing"
    "prod" "production" "admin" "administrator" "root"
    "webmaster" "profile" "dashboard" "settings" "options"
    "index" "files" "releases" "snapshots"})

(def ^{:private true} constituent-chars
  (->> [[\a \z] [\A \Z] [\0 \9]]
       (mapcat (fn [[x y]] (range (int x) (inc (int y)))))
       (map char)
       vec))

(defn rand-string
  "Generates a random string of [A-z0-9] of length n."
  [n]
  (str/join (repeatedly n #(rand-nth constituent-chars))))

(defn get-time []
  (Date.))

(defn bcrypt [s]
  (creds/hash-bcrypt s :work-factor (:bcrypt-work-factor config)))

(defn find-user [username]
  (sql/find-user {:username username}
                 {:connection (:db config)
                  :result-set-fn first}))

(defn find-user-by-user-or-email [username-or-email]
  (sql/find-user-by-user-or-email {:username_or_email username-or-email}
                                  {:connection (:db config)
                                   :result-set-fn first}))

(defn find-user-by-password-reset-code [reset-code]
  (sql/find-user-by-password-reset-code {:reset_code reset-code
                                         :reset_code_created_at
                                         (-> 1 time/days time/ago time.coerce/to-long)}
                                        {:connection (:db config)
                                         :result-set-fn first}))

(defn find-groupnames [username]
  (sql/find-groupnames {:username username}
                       {:connection (:db config)
                        :row-fn :name}))

(defn group-membernames [groupname]
  (sql/group-membernames {:groupname groupname}
                         {:connection (:db config)
                          :row-fn :user}))

(defn group-keys [groupname]
  (sql/group-keys {:groupname groupname}
                  {:connection (:db config)
                   :row-fn :pgp_key}))

(defn jars-by-username [username]
  (sql/jars-by-username {:username username}
                        {:connection (:db config)}))

(defn jars-by-groupname [groupname]
  (sql/jars-by-groupname {:groupname groupname}
                         {:connection (:db config)}))

(defn recent-versions
  ([groupname jarname]
   (sql/recent-versions {:groupname groupname
                         :jarname jarname}
                        {:connection (:db config)}))
  ([groupname jarname num]
   (sql/recent-versions-limit {:groupname groupname
                               :jarname jarname
                               :num num}
                              {:connection (:db config)})))

(defn count-versions [groupname jarname]
  (sql/count-versions {:groupname groupname
                       :jarname jarname}
                      {:connection (:db config)
                       :result-set-fn first
                       :row-fn :count}))

(defn recent-jars []
  (sql/recent-jars {} {:connection (:db config)}))

(defn jar-exists [groupname jarname]
  (sql/jar-exists {:groupname groupname
                   :jarname jarname}
                  {:connection (:db config)
                   :result-set-fn first
                   :row-fn #(= % 1)}))

(defn find-jar
  ([groupname jarname]
   (sql/find-jar {:groupname groupname
                  :jarname jarname}
                 {:connection (:db config)
                  :result-set-fn first}))
  ([groupname jarname version]
   (sql/find-jar-versioned {:groupname groupname
                            :jarname jarname
                            :version version}
                           {:connection (:db config)
                            :result-set-fn first})))

(defn all-projects [offset-num limit-num]
  (sql/all-projects {:num limit-num
                     :offset offset-num}
                    {:connection (:db config)}))

(defn count-all-projects []
  (sql/count-all-projects {}
                          {:connection (:db config)
                           :result-set-fn first
                           :row-fn :count}))

(defn count-projects-before [s]
  (sql/count-projects-before {:s s}
                             {:connection (:db config)
                              :result-set-fn first
                              :row-fn :count}))

(defn browse-projects [current-page per-page]
  (vec
   (map
    #(find-jar (:group_name %) (:jar_name %))
    (all-projects
     (* (dec current-page) per-page)
     per-page))))

(def write-executor (memoize #(Executors/newSingleThreadExecutor)))

(def ^:private ^:dynamic *in-executor* nil)

(defn serialize-task* [task-name task]
  (if *in-executor*
    (task)
    (binding [*in-executor* true]
      (let [bound-f (bound-fn []
                      (try
                        (task)
                        (catch Throwable e
                          e)))
            response (deref
                      (.submit (write-executor) bound-f)
                      10000 ::timeout)]
        (cond
          (= response ::timeout) (throw
                                  (ex-info
                                   "Timed out waiting for serialized task to run"
                                   {:name task-name}))
          (instance? Throwable response) (throw
                                          (ex-info "Serialized task failed"
                                                   {:name task-name}
                                                   response))
          :default response)))))

(defmacro serialize-task [name & body]
  `(serialize-task* ~name
                    (fn [] ~@body)))

(defn add-user [email username password pgp-key]
  (let [record {:email email, :username username, :password (bcrypt password),
                :pgp_key pgp-key :created (get-time)}
        groupname (str "org.clojars." username)]
    (serialize-task :add-user
                    (sql/insert-user! record
                                      {:connection (:db config)})
                    (sql/insert-group! {:groupname groupname :username username}
                                       {:connection (:db config)}))
    record))

(defn update-user [account email username password pgp-key]
  (let [fields {:email email
                :username username
                :pgp_key pgp-key
                :account account}
        fields (if (empty? password)
                 fields
                 (assoc fields :password (bcrypt password)))]
    (serialize-task :update-user
                    (sql/update-user! fields
                                      {:connection (:db config)}))
    fields))

(defn update-user-password [reset-code password]
  (assert (not (str/blank? reset-code)))
  (serialize-task :update-user-password
                    (sql/update-user-password! {:password (bcrypt password)
                                                :reset_code reset-code}
                                               {:connection (:db config)})))

  ;; Password resets
  ;; Reference:
  ;; https://github.com/xavi/noir-auth-app/blob/master/src/noir_auth_app/models/user.clj
  ;; https://github.com/weavejester/crypto-random/blob/master/src/crypto/random.clj
  ;; https://jira.atlassian.com/browse/CWD-1897?focusedCommentId=196759&page=com.atlassian.jira.plugin.system.issuetabpanels:comment-tabpanel#comment-196759
(defn generate-secure-token [size]
  (let [seed (byte-array size)]
    ;; http://docs.oracle.com/javase/6/docs/api/java/security/SecureRandom.html
    (.nextBytes (SecureRandom/getInstance "SHA1PRNG") seed)
    seed))

(defn hexadecimalize [byte-array]
                                        ; converts byte array to hex string
                                        ; http://stackoverflow.com/a/8015558/974795
  (str/lower-case (apply str (map #(format "%02X" %) byte-array))))

(defn set-password-reset-code! [username-or-email]
  (let [reset-code (hexadecimalize (generate-secure-token 20))]
    (serialize-task :set-password-reset-code
                    (sql/set-password-reset-code! {:reset_code reset-code
                                                   :reset_code_created_at (get-time)
                                                   :username_or_email username-or-email}
                                                  {:connection (:db config)}))
    reset-code))

(defn add-member [groupname username added-by]
  (serialize-task :add-member
                  (sql/add-member! {:groupname groupname
                                    :username username
                                    :added_by added-by}
                                   {:connection (:db config)})))

(defn check-and-add-group [account groupname]
  (when-not (re-matches #"^[a-z0-9-_.]+$" groupname)
    (throw (Exception. (str "Group names must consist of lowercase "
                            "letters, numbers, hyphens, underscores "
                            "and full-stops."))))
  (let [members (group-membernames groupname)]
    (if (empty? members)
      (if (reserved-names groupname)
        (throw (Exception. (str "The group name "
                                groupname
                                " is already taken.")))
        (add-member groupname account "clojars"))
      (when-not (some #{account} members)
        (throw (Exception. (str "You don't have access to the "
                                groupname " group.")))))))

(defn add-jar [account {:keys [group name version
                               description homepage authors]}]
  (check-and-add-group account group)
  (serialize-task :add-jar
                  (sql/add-jar! {:groupname group
                                 :jarname   name
                                 :version   version
                                 :user      account
                                 :created    (get-time)
                                 :description description
                                 :homepage   homepage
                                 :authors    (str/join ", " (map #(.replace % "," "")
                                                                 authors))}
                                {:connection (:db config)})))

(defn delete-jars [group-id & [jar-id version]]
  (serialize-task :delete-jars
                  (let [coords {:group_id group-id}]
                    (if jar-id
                      (let [coords (assoc coords :jar_id jar-id)]
                        (if version
                          (sql/delete-jar-version! (assoc coords :version version)
                                                   {:connection (:db config)})
                          (sql/delete-jars! coords
                                            {:connection (:db config)})))
                      (sql/delete-groups-jars! coords
                                               {:connection (:db config)})))))

;; does not delete jars in the group. should it?
(defn delete-groups [group-id]
  (serialize-task :delete-groups
                  (sql/delete-group! {:group_id group-id}
                                     {:connection (:db config)})))

(defn find-jars-information
  ([group-id]
   (find-jars-information group-id nil))
  ([group-id artifact-id]
   (if artifact-id
     (sql/find-jars-information {:group_id group-id
                                 :artifact_id artifact-id}
                                {:connection (:db config)})
     (sql/find-groups-jars-information {:group_id group-id}
                                       {:connection (:db config)}))))

(defn promote [group name version]
  (serialize-task :promote
                  (sql/promote! {:group_id group
                                 :artifact_id name
                                 :version version
                                 :promoted_at (get-time)})))

(defn promoted? [group-id artifact-id version]
  (sql/promoted {:group_id group-id
                 :artifact_id artifact-id
                 :version version}
                {:connection (:db config)
                 :result-set-fn first
                 :row-fn :promoted_at}))
