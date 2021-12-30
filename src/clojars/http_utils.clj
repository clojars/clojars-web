(ns clojars.http-utils
  (:require
   [ring.middleware.session :refer [wrap-session]]
   [ring.middleware.session.memory :as mem]))

(defn wrap-cors-headers [handler]
  (fn [req]
    (let [response (handler req)]
      (if (= 200 (:status response))
                     (update-in response [:headers] assoc "Access-Control-Allow-Origin" "*")
                     response
                     ))))

(defn wrap-x-frame-options [f]
  (fn [req] (update-in (f req) [:headers] assoc "X-Frame-Options" "DENY")))

(defn https-request? [req]
  (or (= (:scheme req) :https)
      (= (get-in req [:headers "x-forwarded-proto"]) "https")))

(def ^:private session-store-atom
  (atom {}))

(defn clear-sessions!
  "Clears all active sessions. Should only be used in testing!"
  []
  (reset! session-store-atom {}))

(defn wrap-secure-session [f]
  (let [mem-store (mem/memory-store session-store-atom)
        secure-session (wrap-session f {:cookie-attrs {:secure true
                                                       :http-only true}
                                        :store mem-store})
        regular-session (wrap-session f {:cookie-attrs {:http-only true}
                                         :store mem-store})]
    (fn [req]
      (if (https-request? req)
        (secure-session req)
        (regular-session req)))))
