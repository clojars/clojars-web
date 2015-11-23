(ns clojars.ring-servlet-patch
  (:import [javax.servlet.http HttpServletResponseWrapper]
           [org.eclipse.jetty.server Request]
           [org.eclipse.jetty.server.handler AbstractHandler]))


(defn response-wrapper [response]
  (proxy [HttpServletResponseWrapper] [response]
    (setHeader [name value]
      (if (and (= name "status-message") value)
        (.setStatus response (.getStatus response) value)
        (proxy-super setHeader name value)))))

(defn ^AbstractHandler handler-wrapper [handler]
  (proxy [AbstractHandler] []
    (handle [target ^Request base-request request response]
      (let [response (response-wrapper response)]
        (.handle handler target base-request request response)))))

(defn use-status-message-header [server]
  (let [handler (.getHandler server)]
    (.setHandler server (handler-wrapper handler))))
