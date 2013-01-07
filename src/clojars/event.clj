(ns clojars.event
  (:require [clojure.java.io :as io]
            [clojars.config :refer [config]]
            [clojars.index :as index])
  (:import (org.apache.commons.codec.digest DigestUtils)))

;; TODO: this will be triggered before config has been set up right
(defonce _ (delay (.mkdirs (io/file (:event-dir config)))))

(defn record [type event]
  (let [filename (index/event-log-file type)
        content (prn-str (assoc event :at (java.util.Date.)))]
    (locking #'record
      (spit filename content :append true))))

(defn record-deploy [{:keys [group artifact-id version]} deployed-by file]
  (record :deploy {:group group
                   :artifact-id artifact-id
                   :version version
                   :deployed-by deployed-by
                   :filename (str file)
                   :sha1 (DigestUtils/shaHex (io/input-stream file))}))
