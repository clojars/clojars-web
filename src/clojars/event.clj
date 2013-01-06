(ns clojars.event
  (:require [clojure.java.io :as io]
            [clojars.config :refer [config]])
  (:import (org.apache.commons.codec.digest DigestUtils)))

(defn- event-log-file [type]
  (io/file (config :event-dir) (str (name type) ".clj")))

(defn record [type event]
  (let [filename (event-log-file type), content (prn-str event)]
    (locking #'record
      ;; TODO: keep file open
      (spit filename content :append))))

(defn record-deploy [{:keys [group artifact-id version]} deployed-by filename]
  (record :deploy {:group group
                   :artifact-id artifact-id
                   :version version
                   :deployed-by deployed-by
                   :filename filename
                   :sha1 (-> (io/file filename)
                             (io/input-stream)
                             (DigestUtils/shaHex))}))
