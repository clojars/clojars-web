(ns clojars.middleware)

;; Adapted from https://gist.github.com/dannypurcell/8215411
(defn wrap-ignore-trailing-slash
  "Modifies the request uri before calling the handler.
  Removes a single trailing slash from the end of the uri if present.

  Useful for handling optional trailing slashes until Compojure's route matching syntax supports regex.
  Adapted from http://stackoverflow.com/questions/8380468/compojure-regex-for-matching-a-trailing-slash"
  [handler]
  (fn [request]
    (let [uri (:uri request)]
      (handler (assoc request :uri (if (and (not (= "/" uri))
                                            (.endsWith ^String uri "/"))
                                     (subs uri 0 (dec (count uri)))
                                     uri))))))

