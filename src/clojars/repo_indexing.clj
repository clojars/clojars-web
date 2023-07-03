(ns clojars.repo-indexing
  (:require
   [clojars.event :as event]
   [clojars.retry :as retry]
   [clojars.s3 :as s3]
   [clojars.web.common :as common]
   [clojars.web.safe-hiccup :as safe-hiccup]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [hiccup.element :as el])
  (:import
   (java.io
    ByteArrayInputStream)))

(set! *warn-on-reflection* true)

(defn- last-segment
  [path]
  (peek (str/split path #"/")))

(defn- entry-line-dispatch
  [entry]
  (if (:Prefix entry)
    :prefix
    :file))

(defmulti entry-line
  #'entry-line-dispatch)

(def ^:private name-col-width 50)
(def ^:private date-col-width 16)
(def ^:private size-col-width 10)

(defn- blanks
  [max-len content-str]
  (apply str (repeat (- max-len (count content-str)) " ")))

(def ^:private dash "-")

(defmethod entry-line :prefix
  [{:keys [Prefix]}]
  (let [suffix (format "%s/" (last-segment Prefix))]
    (list
     (el/link-to {:title suffix} suffix suffix)
     (blanks name-col-width suffix)
     (blanks date-col-width dash)
     dash
     (blanks size-col-width dash)
     dash
     "\n")))

(defmethod entry-line :file
  [{:keys [Key LastModified Size]}]
  (let [file-name (last-segment Key)
        size (str Size)]
    (list
     (el/link-to {:title file-name} file-name file-name)
     (blanks name-col-width file-name)
     (common/format-date-with-time LastModified)
     (blanks size-col-width size)
     size
     "\n")))

(defn- generate-index
  ^String
  [path entries]
  (safe-hiccup/html5
   {:lang "en"}
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width,initial-scale=1"}]
    [:title (format "Clojars Repository: %s" (or path "/"))]]
   [:body
    [:header
     [:h1 (or path "/")]]
    [:hr]
    [:main
     [:pre#contents
      (when (some? path)
        (list
         (el/link-to "../" "../")
         "\n"))
      (mapcat entry-line entries)]]
    [:hr]]))

(defn- sort-entries
  [entries]
  (sort
   (fn [e1 e2]
     (cond
       (and (:Prefix e1) (:Prefix e2)) (compare (:Prefix e1) (:Prefix e2))
       (:Prefix e1)                    -1
       (:Prefix e2)                    1
       :else                           (compare (:Key e1) (:Key e2))))
   entries))

(def ^:private index-file-name "index.html")

(defn- get-path-entries
  [repo-bucket path]
  (->> (s3/list-entries repo-bucket path)
       ;; filter out index files
       (remove (fn [{:keys [Key]}]
                 (and Key
                      (str/ends-with? Key index-file-name))))
       (sort-entries)))

(defn- normalize-path
  [path]
  (let [path (cond
               (str/blank? path)           nil
               (= "/" path)                nil
               (str/starts-with? path "/") (subs path 1)
               :else                       path)]
    (if (and (some? path)
             (not (str/ends-with? path "/")))
      (str path "/")
      path)))

(defn- handler
  [{:keys [error-reporter repo-bucket]} type {:as _data :keys [path]}]
  (when (= :repo-path-needs-index type)
    (let [path (normalize-path path)]
      (retry/retry {:error-reporter error-reporter}
                   (when-some [entries (get-path-entries repo-bucket path)]
                     (let [key (if path
                                 (format "%s%s" path index-file-name)
                                 index-file-name)
                           index (generate-index path entries)]
                       (s3/put-object repo-bucket
                                      key
                                      (io/input-stream (ByteArrayInputStream. (.getBytes index)))
                                      {:ACL "public-read"
                                       :ContentType (s3/content-type key)})))))))

(defn repo-indexing-component
  "Handles async repo-indexing for a path. Needs the error-reporter & repo-bucket components."
  []
  (event/handler-component #'handler))
