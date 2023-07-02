(ns clojars.repo-indexing
  (:require
   [clojars.s3 :as s3]
   [clojars.web.common :as common]
   [clojars.web.safe-hiccup :as safe-hiccup]
   [clojure.string :as str]
   [hiccup.element :as el]))

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

(defn generate-index
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

(defn get-path-entries
  [repo-bucket path]
  (->> (s3/list-entries repo-bucket path)
       ;; filter out index files
       (remove (fn [{:keys [Key]}]
                 (and Key
                      (str/ends-with? Key "index.html"))))
       (sort-entries)))

(defn normalize-path
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
