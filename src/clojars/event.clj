(ns clojars.event
  (:refer-clojure :exclude [load])
  (:require [clojure.java.io :as io]
            [clojars.config :refer [config]])
  (:import (org.apache.commons.codec.digest DigestUtils)))

(defn event-log-file [type]
  (io/file (config :event-dir) (str (name type) ".clj")))

;; TODO: this will be triggered before config has been set up right
(defonce _ (delay (.mkdirs (io/file (:event-dir config)))))

(defn record [type event]
  (let [filename (event-log-file type)
        content (prn-str (assoc event :at (java.util.Date.)))]
    (locking #'record
      (spit filename content :append true))))

(defn record-deploy [{:keys [group-id artifact-id version]} deployed-by file]
  (record :deploy {:group-id group-id
                   :artifact-id artifact-id
                   :version version
                   :deployed-by deployed-by
                   :filename (str file)
                   :sha1 (DigestUtils/shaHex (io/input-stream file))}))

(defonce users (atom {}))

(defonce memberships (atom {}))

(defn add-user [users {:keys [username email] :as user}]
  (-> users
      (update-in [username] merge user)
      (update-in [email] merge user)))

(defn add-member [memberships {:keys [group-id username]}]
  (update-in memberships [group-id] (fnil conj #{}) username))

(defn add-user-membership [users {:keys [group-id username]}]
  (update-in users [username :groups] (fnil conj #{}) group-id))

(defn load-users [file]
  (with-open [r (io/reader file)]
    (swap! users #(reduce add-user % (map read-string (line-seq r))))))

(defn load-memberships [file]
  (with-open [r (io/reader file)]
    (swap! memberships #(reduce add-member % (map read-string (line-seq r))))
    (swap! users #(reduce add-user-membership %
                          (map read-string (line-seq r))))))

(defn load []
  (load-users (event-log-file :user))
  (load-memberships (event-log-file :membership)))