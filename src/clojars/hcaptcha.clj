(ns clojars.hcaptcha
  "See https://docs.hcaptcha.com/#verify-the-user-response-server-side"
  (:require
   [clojars.log :as log]
   [clojars.remote-service :as remote-service :refer [defendpoint]]
   [clojure.set :as set]))

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

(defendpoint -validate-hcaptcha-response
  [_client site-key secret hcaptcha-response]
  {:method :post
   :url "https://api.hcaptcha.com/siteverify"
   :form-params {:response hcaptcha-response
                 :secret secret
                 :site-key site-key}})

;; These responses from hcaptcha indicate a misconfiguration or a code bug, so
;; we will throw and error for them.
(def ^:private invalid-error-codes
  #{"bad-request"
    "invalid-input-secret"
    "invalid-or-already-seen-response"
    "missing-input-secret"
    "sitekey-secret-mismatch"})

(defn- log-and-maybe-throw
  [{:as _response :keys [error-codes success]}]
  (let [log-data {:tag :hcaptcha-response
                  :error-codes error-codes
                  :success? success}]
    (log/info log-data)
    (when (seq (set/intersection invalid-error-codes (set error-codes)))
      (throw (ex-info "Invalid hcaptcha configuration" log-data)))))

(defn valid-response?
  [hcaptcha hcaptcha-response]
  (let [response (-validate-hcaptcha-response (:client hcaptcha)
                                              (site-key hcaptcha)
                                              (secret hcaptcha)
                                              hcaptcha-response)]
    (log-and-maybe-throw response)
    (:success response)))

(defn new-hcaptcha
  [config]
  (map->Hcaptcha {:config config
                  :client (remote-service/new-http-remote-service)}))
