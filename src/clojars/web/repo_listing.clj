(ns clojars.web.repo-listing
  (:require
   [clojars.maven :as maven]
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

;; Public for use in tests
(defn cache-file
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

(def ^:private not-found-response
  (-> (safe-hiccup/html5
       {:lang "en"}
       [:head [:title "404 Not Found"]]
       [:body [:h1 "404 Not Found"]])
      (ring.response/not-found)
      (ring.response/content-type "text/html;charset=utf-8")))

(defn- get-cached-response
  [cache-path requested-path]
  (let [cached-file (cache-file cache-path requested-path)]
    (when (.exists cached-file)
      (let [age (file-age cached-file)]
        (when-not (> age max-age)
          (let [response (edn/read-string (slurp cached-file))]
            ;; We don't cache the full :not-found response since it is static to
            ;; save disk space
            [(if (= :not-found response) not-found-response response) age]))))))

(defn- cache-response
  [cache-path requested-path response]
  (spit (cache-file cache-path requested-path)
        (binding [*print-length* nil]
          (pr-str (if (= not-found-response response) :not-found response))))
  [response 0])

(defn- response
  [repo-bucket path]
  (if-some [entries (seq (sort-entries (s3/list-entries repo-bucket path)))]
    (-> (index path entries)
        (ring.response/response)
        (ring.response/content-type "text/html;charset=utf-8"))
    not-found-response))

(defn- validate-path
  "Checks path to see if it is one we would even have in s3, returning a 404 w/o
  calling s3 or caching if not. This is to reduce the work we do & disk space we
  use for caching."
  [path]
  (when (and path (not (re-find maven/repo-path-regex path)))
    [not-found-response 0]))

(defn index-for-path
  [{:keys [cache-path repo-bucket]} path]
  (let [path (normalize-path path)
        [response age] (or (validate-path path)
                           (get-cached-response cache-path path)
                           (cache-response cache-path path (response repo-bucket path)))]
    (ring.response/header response
                          "Cache-Control" (format "s-maxage=%s" (- max-age age)))))

(defn repo-lister
  [cache-path]
  ;; :repo-bucket gets assoc'ed on to this by component
  {:cache-path cache-path})
