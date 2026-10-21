(ns clojars.remote-service
  "A remote service abstraction to consolidate http request handing and
  to ease testing."
  (:require
   [cheshire.core :as json]
   [clojars.http-client :as http]
   [clojars.log :as log]))

(defprotocol RemoteService
  (-remote-call [self request-info]))

(defn- parse-body
  [{:keys [response-type] :or {response-type :json}} body]
  (case response-type
    :json (json/parse-string body true)
    :text body))

(defn- loggable-request
  [{:as request-info :keys [url-pattern]}]
  (cond-> request-info
    url-pattern (assoc :url url-pattern)))

(defrecord HttpRemoteService []
  RemoteService
  (-remote-call [_self request-info]
    (log/with-context {:tag :http-request
                       :request (loggable-request request-info)}
      (let [response (http/request request-info)]
        (if (or (:error response) (>= (:status response) 400))
          (let [response (dissoc response :opts)]
            (log/error {:status :failed
                        :response response})
            (throw (ex-info "HTTP request failed"
                            {:request (loggable-request request-info)
                             :response response})))
          (do
            (log/info {:status :success})
            (parse-body request-info (:body response))))))))

(def ^:private ^:dynamic *mock-calls* nil)
(def ^:private ^:dynamic *mock-responders* nil)

(defrecord MockRemoteService []
  RemoteService
  (-remote-call [_self request-info]
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
  "Creates a function to call a remote endpoint.
   
  * The response body is parsed as JSON unless `request-info` sets `:response-type :text`.
  * If `request-info` has a `:url-pattern` (an RFC 6570 URI template), it is logged instead
  of `:url`, so sensitive values in the URL aren't logged."
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
