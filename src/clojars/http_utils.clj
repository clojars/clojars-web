(ns clojars.http-utils
  (:require
   [clojure.string :as str]
   [ring.middleware.session :refer [wrap-session]]
   [ring.middleware.session.memory :as mem]
   [ring.util.response :refer [content-type response]]))

(defn wrap-cors-headers [handler]
  (fn [req]
    (let [response (handler req)]
      (if (= 200 (:status response))
                     (update-in response [:headers] assoc "Access-Control-Allow-Origin" "*")
                     response))))

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

(defn- content-security-policy
  [{:as _request ::keys [extra-csp-img-src]}]
  (str/join
   ";"
   ;; Load anything from the clojars domain
   ["default-src 'self'"
    ;; Load images from clojars domain along with dnsimple's logo and any extra
    ;; allowed sources per page
    (apply str "img-src 'self' https://cdn.dnsimple.com "
           (interpose " " extra-csp-img-src))]))

(def ^:private permissions-policy
  ;; We only need to write to the clipboard
  ;; Generated using https://www.permissionspolicy.com/
  "accelerometer=(), ambient-light-sensor=(), autoplay=(), battery=(), camera=(), cross-origin-isolated=(), display-capture=(), document-domain=(), encrypted-media=(), execution-while-not-rendered=(), execution-while-out-of-viewport=(), fullscreen=(), geolocation=(), gyroscope=(), keyboard-map=(), magnetometer=(), microphone=(), midi=(), navigation-override=(), payment=(), picture-in-picture=(), publickey-credentials-get=(), screen-wake-lock=(), sync-xhr=(), usb=(), web-share=(), xr-spatial-tracking=(), clipboard-read=(), clipboard-write=(self), gamepad=(), speaker-selection=(), conversion-measurement=(), focus-without-user-activation=(), hid=(), idle-detection=(), interest-cohort=(), serial=(), sync-script=(), trust-token-redemption=(), window-placement=(), vertical-scroll=()")

(defn wrap-additional-security-headers [f]
  (fn [req]
    (let [response (f req)]
      (-> response
          ;; Restrict content loading to help prevent xss
          (assoc-in [:headers "Content-Security-Policy"] (content-security-policy response))
          ;; Don't allow usage of any advanced js features
          (assoc-in [:headers "Permissions-Policy"] permissions-policy)
          ;; Clojars URLs don't have sensitive content, so we could get away with
          ;; "unsafe-url" here. But that will give us a poorer score on
          ;; https://securityheaders.com and lead to vulnerability reports from
          ;; folks using automated tools, so we set this to just not share the
          ;; referrer with non-secure sites.
          (assoc-in [:headers "Referrer-Policy"] "no-referrer-when-downgrade")))))

(defn with-extra-img-src
  "Adds an additional img-src to our content-security-policy."
  [src body]
  (-> body
      (response)
      (content-type "text/html;charset=utf-8")
      (assoc ::extra-csp-img-src src)))
