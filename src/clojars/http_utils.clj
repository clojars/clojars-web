(ns clojars.http-utils
  (:require
   [aging-session.event :as aging-session-event]
   [aging-session.memory :as aging-session]
   [clojure.string :as str]
   [ring.middleware.session :refer [wrap-session]]
   [ring.util.response :refer [content-type response]]))

(defn wrap-cors-headers [handler]
  (fn [req]
    (let [response (handler req)]
      (if (= 200 (:status response))
        (update response :headers assoc "Access-Control-Allow-Origin" "*")
        response))))

(defn https-request? [req]
  (or (= :https (:scheme req))
      (= "https" (get-in req [:headers "x-forwarded-proto"]))))

(def ^:private session-store-atom
  (atom {}))

(defn clear-sessions!
  "Clears all active sessions. Should only be used in testing!"
  []
  (reset! session-store-atom {}))

(defn wrap-secure-session [f]
  (let [mem-store (aging-session/aging-memory-store
                   :session-atom     session-store-atom
                   :refresh-on-write true
                   ;; Allow sessions to remain active for 48 hours
                   :events           [(aging-session-event/expires-after 172800)])
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
  [{:as _request ::keys [extra-csp-srcs]}]
  (str/join
   ";"
   (concat
    ;; Load anything from the clojars domain
    ["default-src 'self'"
     ;; Load images from clojars domain along with dnsimple's logo and any extra
     ;; allowed sources per page
     (apply str "img-src 'self' https://cdn.dnsimple.com "
            (interpose " " (:img-src extra-csp-srcs)))]
    (for [[k v] (dissoc extra-csp-srcs :img-src)]
      (apply str (name k) " 'self' " (interpose " " v))))))

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

(defn with-extra-csp-srcs
  "Adds an additional *-src values to our content-security-policy."
  [srcs body]
  (-> body
      (response)
      (content-type "text/html;charset=utf-8")
      (assoc ::extra-csp-srcs srcs)))
