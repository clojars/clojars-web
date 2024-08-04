(ns clojars.ring-servlet-patch
  (:import
   (jakarta.servlet.http
    HttpServletResponse
    HttpServletResponseWrapper)
   (org.eclipse.jetty.server
    Response
    Server)
   (org.eclipse.jetty.server.handler
    HandlerWrapper)
   (org.eclipse.jetty.servlet
    ServletContextHandler)))

(set! *warn-on-reflection* true)

(defn response-wrapper
  [^Response response]
  (proxy [HttpServletResponseWrapper] [response]
    (setHeader [name value]
      (if (and (= "status-message" name) value)
        ;; Jetty ignores the reason passed to HttpServletResponse#setStatus(),
        ;; so we have to call a method on Jetty's response impl instead.
        (.setStatusWithReason ^Response response (.getStatus response) value)
        (.setHeader ^HttpServletResponse response name value)))))

(defn handler-wrapper
  ^HandlerWrapper []
  (proxy [HandlerWrapper] []
    (handle [target base-request request response]
      (let [response (response-wrapper response)
            ;; This prevents reflection on the proxy-super call
            ;; by hinting the explicit `this`.
            ^HandlerWrapper this this]
        (proxy-super handle target base-request request response)))))

(defn use-status-message-header
  "This adds a wrapper around the servlet handler to override .setHeader on the response.
  We do this so we can set the status message of the response by smuggling it
  through ring as a header, as this setting this is not exposed by ring.

  The status message is used to provide additional context when deploying."
  [^Server server]
  (println "Adding Jetty status-message handler")
  (let [^ServletContextHandler handler (.getHandler server)]
    (.insertHandler handler (handler-wrapper))))
