(ns clojars.from.ring.middleware.session-timeout)

;; This NS was copied from the MIT-licensed
;; https://github.com/ring-clojure/ring-session-timeout, and modified to not

;; create a session if one does not already exist.
(defn- current-time []
  (quot (System/currentTimeMillis) 1000))

(defn- session-idle-expired? [session]
  (let [end-time (::idle-timeout session)]
    (and end-time (< end-time (current-time)))))

(defn- idle-session-timeout-response [response req-session timeout]
  (when response
    (let [session (:session response req-session)]
      (if (seq session)
        (let [end-time (+ (current-time) timeout)]
          (assoc response :session (assoc session ::idle-timeout end-time)))
        (dissoc response :session)))))

(defn wrap-idle-session-timeout
  "Middleware that times out idle sessions after a specified number of seconds.

  If a session is timed out, the timeout-response option is returned. This is
  usually a redirect to the login page. Alternatively, the timeout-handler
  option may be specified. This should contain a Ring handler function that
  takes the current request and returns a timeout response.

  The following options are accepted:

  :timeout          - the idle timeout in seconds (default 600 seconds)
  :timeout-response - the response to send if an idle timeout occurs
  :timeout-handler  - the handler to run if an idle timeout occurs"
  {:arglists '([handler options])}
  [handler {:keys [timeout timeout-response timeout-handler] :or {timeout 600}}]
  {:pre [(integer? timeout)
         (if (map? timeout-response)
           (nil? timeout-handler)
           (ifn? timeout-handler))]}
  (fn
    ([request]
     ;; Not defaulting the session to {} prevents creating a new session for each request.
     ;; This is a change from the original source. - Toby
     (let [session (seq (:session request))]
       (if (session-idle-expired? session)
         (assoc (or timeout-response (timeout-handler request)) :session nil)
         (let [x (idle-session-timeout-response (handler request) session timeout)]
           #_(sc.api/spy [session (:session x)])
           x))))
    ([request respond raise]
     ;; Not defaulting the session to {} prevents creating a new session for each request.
     ;; This is a change from the original source. - Toby
     (let [session (:session request)]
       (if (session-idle-expired? session)
         (if timeout-response
           (respond (assoc timeout-response :session nil))
           (timeout-handler request #(respond (assoc % :session nil)) raise))
         (handler request
                  #(respond (idle-session-timeout-response % session timeout))
                  raise))))))
