(ns clojars.web.repo-listing
  (:require
   [clojars.maven :as maven]
   [clojars.repo-indexing :as repo-indexing]
   [clojars.web.safe-hiccup :as safe-hiccup]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [ring.util.response :as ring.response])
  (:import
   (java.io
    File)
   (java.util
    Date)))

(set! *warn-on-reflection* true)

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
  (try
    (let [cached-file (cache-file cache-path requested-path)]
      (when (.exists cached-file)
        (let [age (file-age cached-file)]
          (when-not (> age max-age)
            (let [response (edn/read-string (slurp cached-file))]
              ;; We don't cache the full :not-found response since it is static to
              ;; save disk space
              [(if (= :not-found response) not-found-response response) age])))))
    (catch Exception _)))

(defn- cache-response
  [cache-path requested-path response]
  (spit (cache-file cache-path requested-path)
        (binding [*print-length* nil]
          (pr-str (if (= not-found-response response) :not-found response))))
  [response 0])

(defn- response
  [repo-bucket path]
  (if-some [entries (seq (repo-indexing/get-path-entries repo-bucket path))]
    (-> (repo-indexing/generate-index path entries)
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
  (let [path (repo-indexing/normalize-path path)
        [response age] (or (validate-path path)
                           (get-cached-response cache-path path)
                           (cache-response cache-path path (response repo-bucket path)))]
    (ring.response/header response
                          "Cache-Control" (format "s-maxage=%s" (- max-age age)))))

(defn repo-lister
  [cache-path]
  ;; :repo-bucket gets assoc'ed on to this by component
  {:cache-path cache-path})
