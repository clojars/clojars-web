(ns clojars.hcaptcha
  (:require
   [clojars.remote-service :as remote-service :refer [defendpoint]]))

(def hcaptcha-csp
  (let [hcaptcha-urls ["https://hcaptcha.com" "https://*.hcaptcha.com"]]
    {:connect-src hcaptcha-urls
     :frame-src   hcaptcha-urls
     :script-src  hcaptcha-urls
     :style-src   hcaptcha-urls}))

(defrecord Hcaptcha [config])

(defn site-key
  [hcaptcha]
  (get-in hcaptcha [:config :site-key]))

(defn- secret
  [hcaptcha]
  (get-in hcaptcha [:config :secret]))

(defendpoint -validate-token
  [_client secret token]
  {:method :post
   :url "https://api.hcaptcha.com/siteverify"
   :form-params {:response token
                 :secret secret}})

(defn valid-token?
  [hcaptcha token]
  (let [response (-validate-token (:client hcaptcha)
                                  (secret hcaptcha)
                                  token)]
    (:success response)))

(defn new-hcaptcha
  [config]
  (map->Hcaptcha {:config config
                  :client (remote-service/new-http-remote-service)}))

;; https://docs.hcaptcha.com/#verify-the-user-response-server-side

;; mock for testing
;; store cred in SSM param
;;
;;

;; Verify the User Response Server Side
;;
;; By adding the client side code, you were able to render an hCaptcha widget that
;; identified if users were real people or automated bots. When the captcha
;; succeeded, the hCaptcha script inserted a unique token into your form data.
;;
;; To verify that the token is indeed real and valid, you must now verify it at the API endpoint:
;;
;; https://api.hcaptcha.com/siteverify
;;
;; The endpoint expects a POST request with two parameters: your account secret and
;; the h-captcha-response token sent from your frontend HTML to your backend for
;; verification. You can optionally include the user's IP address as an additional
;; security check. Do not send JSON data: the endpoint expects a standard
;; URL-encoded form POST.
;;
;; A simple test will look like this:
;;
;; curl https://api.hcaptcha.com/siteverify \
;;  -X POST \
;;  -d 'response=CLIENT-RESPONSE&secret=YOUR-SECRET'
;;
