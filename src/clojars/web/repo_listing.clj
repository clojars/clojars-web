(ns clojars.web.repo-listing
  (:require
   [clojars.s3 :as s3]
   [clojars.web.common :as common]
   [clojars.web.safe-hiccup :as safe-hiccup]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [hiccup.element :as el]
   [ring.util.response :as ring.response])
  (:import
   (java.io
    File)
   (java.util
    Date)))

(set! *warn-on-reflection* true)

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

(defn- index
  [path entries]
  (safe-hiccup/html5
   {:lang "en"}
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width,initial-scale=1"}]
    [:title (format "Clojars Repository: %s" path)]]
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

(def ^:private max-age 43200) ;; 12 hours

(defn- cache-file
  ^File
  [cache-path path]
  ;; assumes path has gone through normalize-path already
  (let [path (if path
               ;; strip trailing /
               (subs path 0 (dec (count path)))
               "root")
        f (io/file (format "%s/%s.edn" cache-path path))]
    (io/make-parents f)
    f))

(defn- file-age
  [^File f]
  (-> (- (.getTime (Date.)) (.lastModified f))
      (/ 1000)
      (long)))

(defn- cached-response
  [cache-path requested-path]
  (let [cached-file (cache-file cache-path requested-path)]
    (when (.exists cached-file)
      (let [age (file-age cached-file)]
        (when-not (> age max-age)
          [(edn/read-string (slurp cached-file)) age])))))

(defn- cache-response
  [cache-path requested-path response]
  (spit (cache-file cache-path requested-path)
        (binding [*print-length* nil]
          (pr-str response)))
  response)

(defn- with-maxage
  [r curr-age-in-seconds]
  (ring.response/header r "Cache-Control" (format "s-maxage=%s" (- max-age curr-age-in-seconds))))

(def ^:private not-found
  (-> (safe-hiccup/html5
       {:lang "en"}
       [:head [:title "404 Not Found"]]
       [:body [:h1 "404 Not Found"]])
      (ring.response/not-found)
      (ring.response/content-type "text/html;charset=utf-8")
      (with-maxage 0)))

(defn response
  [{:keys [cache-path repo-bucket]} path]
  (let [path (normalize-path path)]
    (if-some [[response age] (cached-response cache-path path)]
      (with-maxage response age)
      (cache-response
       cache-path
       path
       (if-some [entries (seq (sort-entries (s3/list-entries repo-bucket path)))]
         (-> (index path entries)
             (ring.response/response)
             (ring.response/content-type "text/html;charset=utf-8")
             (with-maxage 0))
         not-found)))))

(defn repo-lister
  [cache-path]
  ;; :repo-bucket gets assoc'ed on to this by component
  {:cache-path cache-path})
