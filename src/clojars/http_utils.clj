(ns clojars.http-utils
  (:require [ring.middleware
             [session :refer [wrap-session]]]))

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

(defn wrap-secure-session [f]
  (let [secure-session (wrap-session f {:cookie-attrs {:secure true
                                                       :http-only true}})
        regular-session (wrap-session f {:cookie-attrs {:http-only true}})]
    (fn [req]
      (if (https-request? req)
        (secure-session req)
        (regular-session req)))))
