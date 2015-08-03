(ns clojars.ring-servlet-patch
  (:require [ring.util.servlet :as ring-servlet])
  (:import (javax.servlet.http HttpServlet
             HttpServletRequest
             HttpServletResponse)))

(defn update-servlet-response-with-message
  "A replacement for ring.util.servlet/update-servlet-response that can
  set the optional status message, which is the only mechanism we have to
  report true failure reasons to aether."
  [^HttpServletResponse response {:keys [status status-message headers body]}]
  (when-not response
    (throw (Exception. "Null response given.")))
  (when status
    (if status-message
      (.setStatus response status status-message)
      (.setStatus response status)))
  (doto response
    (#'ring-servlet/set-headers headers)
    (#'ring-servlet/set-body body)))

(defn patch-ring-servlet! []
  (alter-var-root #'ring-servlet/update-servlet-response
    (constantly update-servlet-response-with-message)))
