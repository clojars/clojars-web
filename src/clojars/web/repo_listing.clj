(ns clojars.web.repo-listing
  (:require
   [clojars.s3 :as s3]
   [clojars.web.common :as common]
   [clojars.web.safe-hiccup :as safe-hiccup]
   [clojure.string :as str]
   [hiccup.element :as el]
   [ring.util.response :as ring.response]))

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

(defn- with-maxage
  [r]
  ;; Instruct fastly to cache this result for 12 hours
  (ring.response/header r "Cache-Control" "s-maxage=43200"))

(def ^:private not-found
  (-> (safe-hiccup/html5
       {:lang "en"}
       [:head [:title "404 Not Found"]]
       [:body [:h1 "404 Not Found"]])
      (ring.response/not-found)
      (ring.response/content-type "text/html;charset=utf-8")
      (with-maxage)))

(defn response
  [s3 path]
  (let [path (normalize-path path)
        entries (sort-entries (s3/list-entries s3 path))]
    (if (seq entries)
      (-> (index path entries)
          (ring.response/response)
          (ring.response/content-type "text/html;charset=utf-8")
          (with-maxage))
      not-found)))
