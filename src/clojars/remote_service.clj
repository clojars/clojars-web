(ns clojars.remote-service
  "A remote service abstraction to consilidate http request handing and
  to ease testing."
  (:require
   [cheshire.core :as json]
   [clj-http.client :as http]))

(defprotocol RemoteService
  (-remote-call [self request-info]))

;; TODO: (toby) logging
(defrecord HttpRemoteService []
  RemoteService
  (-remote-call [self request-info]
    (-> (http/request request-info)
        :body
        (json/parse-string true))))

(def ^:private ^:dynamic *mock-calls* nil)
(def ^:private ^:dynamic *mock-responders* nil)

(defrecord MockRemoteService []
  RemoteService
  (-remote-call [self request-info]
    (when *mock-calls*
      (swap! *mock-calls* conj request-info))
    (when-let [responder (and *mock-responders*
                              (get @*mock-responders* (::endpoint request-info)))]
      (responder request-info))))


;; ----- public api  -----

(defn new-http-remote-service
  []
  (->HttpRemoteService))

(defmacro defendpoint
  "Creates a function to call a remote endpoint."
  ([name args request-info]
   `(defendpoint ~name "" ~args ~request-info))
  ([name docstring args request-info]
   (let [[client] args]
     `(defn ~name
        ~docstring
        ~args
        (-remote-call ~client (assoc ~request-info
                                     ::endpoint (quote ~name)))))))

;; ----- mocking -----

(defn new-mock-remote-service
  []
  (->MockRemoteService))

(defmacro with-mocking
  [& body]
  `(binding [*mock-calls* (atom [])
             *mock-responders* (atom {})]
     ~@body))

(defn get-calls
  []
  (when *mock-calls* @*mock-calls*))

(defn clear-calls
  []
  (when *mock-calls* (reset! *mock-calls* [])))

(defn set-responder
  [endpoint responder]
  (if *mock-responders*
    (swap! *mock-responders* assoc endpoint responder)
    (throw (ex-info "Attempt to set mock outside of with-mocking"
                    {}))))
