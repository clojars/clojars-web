(ns clojars.http-client
  "A wrapper around org.httpkit.client that makes it synchronous without the
  returned promise leaking out to callers."
  (:refer-clojure :exclude [get])
  (:require
   [org.httpkit.client :as http]))

(defn make-client
  []
  (http/make-client nil))

;; 30 seconds
(def TIMEOUT-MS 30000)

(def default-opts
  {:user-agent "clojars.org"})

(defn request
  [opts]
  (let [resp (-> (merge default-opts opts)
                 (http/request)
                 (deref TIMEOUT-MS ::timeout))]
    (if (= ::timeout resp)
      (throw (ex-info "HTTP request timeout" opts))
      resp)))

(defn get
  ([url]
   (get url nil))
  ([url opts]
   (request (assoc opts
                   :method :get
                   :url url))))

(defn head
  ([url]
   (head url nil))
  ([url opts]
   (request (assoc opts
                   :method :head
                   :url url))))

(defn post
  ([url]
   (post url nil))
  ([url opts]
   (request (assoc opts
                   :method :post
                   :url url))))
(defn put
  ([url]
   (put url nil))
  ([url opts]
   (request (assoc opts
                   :method :put
                   :url url))))
