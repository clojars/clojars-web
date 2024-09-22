(ns clojars.ring-servlet-patch
  "The Maven deploy lib prints the HTTP status message on request failure, and is
  the only mechanism we have to signal validation errors to clients.
  Unfortunately, setting the status message is deprecated in the servlet spec,
  so we have to jump through some hoops to set it.

  This sets it by calling a deprecated method on Jetty's Response object, and we
  monkey-patch ring-jetty-adapter to call this for us.

  A prior implementation we tried should have been the \"correct\" way to do
  this (using a Jetty HandlerWrapper), but it only worked intermittently for
  unknown reasons (you can see that impl here:
  https://github.com/clojars/clojars-web/blob/6368d3d9764a26e6f1ec2106de195325b898fc4c/src/clojars/ring_servlet_patch.clj).
  Instead of doing more debugging of Jetty internals, we just opted to do this
  directly in clojure with this hack."
  (:require
   [ring.core.protocols :as ring.protocols]
   [ring.util.jakarta.servlet :as ring.servlet])
  (:import
   (jakarta.servlet.http
    HttpServletResponse)
   (org.eclipse.jetty.server
    Response)))

(set! *warn-on-reflection* true)

;; Copied from ring.util.jakarta.servlet (from ring/ring-jetty-adapter 1.12.1)
;; and modified to set the status message on the response. This should be
;; audited when we upgrade ring-jetty-adapter.
(defn- update-servlet-response
  "Update the HttpServletResponse using a response map. Takes an optional
  AsyncContext."
  ([response response-map]
   (update-servlet-response response nil response-map))
  ([^HttpServletResponse response context response-map]
   (let [{:keys [status status-message headers body]} response-map]
     (when (nil? response)
       (throw (NullPointerException. "HttpServletResponse is nil")))
     (when (nil? response-map)
       (throw (NullPointerException. "Response map is nil")))
     (when status
       (if status-message
         ;; Jetty ignores the reason passed to HttpServletResponse#setStatus(),
         ;; so we have to call a method on Jetty's response impl instead.
         (.setStatusWithReason ^Response response status status-message)
         (.setStatus response status)))
     (#'ring.servlet/set-headers response headers)
     (let [output-stream (#'ring.servlet/make-output-stream response context)]
       (ring.protocols/write-body-to-stream body response-map output-stream)))))

(defn monkey-patch-update-servlet-response-to-send-status-message
  []
  (alter-var-root #'ring.servlet/update-servlet-response (constantly update-servlet-response)))
