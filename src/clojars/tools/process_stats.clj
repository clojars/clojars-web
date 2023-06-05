(ns clojars.tools.process-stats
  "generate usage statistics from web log"
  (:gen-class)
  (:require
   [clj-time.format :as timef]
   [clojars.file-utils :as fu]
   [clojars.util :as util]
   [clojure.java.io :as io]
   [net.cgrand.regex :as re])
  (:import
   java.io.BufferedReader
   java.util.regex.Pattern))

(def time-clf (timef/formatter "dd/MMM/YYYY:HH:mm:ss Z"))
(def time-cdn (timef/formatters :date-time-no-ms))

;; net.cgrand/regex currently doesn't allow Patterns
;; but they're too handy so let's enable them anyway
(extend-type Pattern
  re/RegexValue
  (pattern [re] (.pattern re))
  (groupnames [_] [])
  re/RegexFragment
  (static? [_ _] true))

(def re-legacy-cdn
  "Log format used when we logged from fastly to rackspace cloudfiles"
  (let [field #"\S+"
        nonquote #"[^\" ]+"
        reqline (list [nonquote :as :method] \space
                      [nonquote :as :path])]
    (re/regex \< #"\d+" \>
              [field :as :time] \space
              [field :as :cache-host] \space
              [field :as :endpoint] \: \space
              [field :as :host] \space
              \" [field :as :ident] \" \space
              \" reqline \" \space
              [field :as :status] \space
              [field :as :size]
              #".*")))

(def re-cdn
  "Log format used when logging from fastly to s3"
  (let [field #"\S+"
        nonquote #"[^\" ]+"
        reqline (list [nonquote :as :method] \space
                      [nonquote :as :path] \space
                      [nonquote :as :http-version])]
    (re/regex \< #"\d+" \>
              [field :as :time] \space
              [field :as :cache-host] \space
              [field :as :endpoint] \: \space
              [field :as :host] \space
              \" reqline \" \space
              [field :as :status] \space
              [field :as :size]
              #".*")))

(def re-path
  (let [segment #"[^/?]+"
        sep #"/+"]
    (re/regex (re/? sep "repo") sep
              [(re/* segment sep) segment :as :group] sep
              [segment :as :name] sep
              [segment :as :version] sep
              segment \.
              [#"\w+" :as :ext])))

(defn is-legacy? [line]
  (.contains line " \"-\" "))

(defn parse-path [s]
  (when s
    (when-let [m (re/exec re-path s)]
      {:name (:name m)
       :group (fu/path->group (:group m))
       :version (:version m)
       :ext (:ext m)})))

(defn parse-line [line]
  (let [legacy? (is-legacy? line)
        m (re/exec (if legacy? re-legacy-cdn re-cdn) line)]
    (merge
     (parse-path (:path m))
     {:status (util/parse-long (:status m))
      :method (:method m)
      :size (util/parse-long (:size m))
      :time (when (:time m) (try (timef/parse time-cdn (:time m))
                                 (catch IllegalArgumentException _)))})))

(defn valid-download? [m]
  (and m
       (= (:status m) 200)
       (= (:method m) "GET")
       (= (:ext m) "jar")))

(def as-year-month (partial timef/unparse (timef/formatters :year-month)))

(defn compute-stats [lines]
  (->> lines
       (map parse-line)
       (filter valid-download?)
       (map (juxt :group :name :version))
       (frequencies)
       (reduce-kv (fn [acc [a g v] n] (assoc-in acc [[a g] v] n)) {})))

(defn process-log [logfile]
  (with-open [rdr (io/reader logfile)]
    (compute-stats (line-seq rdr))))

(defn -main [& _]
  (-> *in*
      BufferedReader.
      line-seq
      compute-stats
      prn))
