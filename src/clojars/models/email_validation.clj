(ns clojars.models.email-validation
  (:require
   [clojars.log :as log]
   [clojars.remote-service :as remote-service :refer [defendpoint]]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(defn- auth-headers
  [{:keys [config]}]
  {"Authorization" (format "Bearer %s" (:user-check-api-key config))})

(defn- api-url
  [{:keys [config]}]
  (:user-check-api-url config))

(defendpoint check-email
  [client email-address]
  {:method :get
   :headers (auth-headers client)
   :url (format "%s/email/%s?include_mx=false" (api-url client) email-address)})

(defn- disposable-email-via-user-check?
  [client email-address]
  (try
    (let [response (check-email client email-address)]
      (boolean (:disposable response)))
    (catch Exception e
      (log/error {:exception e})
      ;; In case of error, let the address through, as most emails are legit
      false)))

(defn new-email-validation-client
  [config]
  (assoc (remote-service/new-http-remote-service) :config config))

(def ^:private disposable-email-blocklist
  (into #{}
        (remove #(str/starts-with? % "#"))
        (line-seq (io/reader (io/resource "disposable_email_blocklist.conf")))))

(defn- domain
  [email-address]
  (let [[_ domain] (re-find #".+@(.+)$" (str/lower-case email-address))]
    domain))

(defn allowed-email?
  "This checks the domain of the given address against a static list from
  https://github.com/disposable-email-domains/disposable-email-domains. If the domain is not on that list,
  it calls the usercheck.com API, as it is more up-to-date. If that API call fails for some reason,
  we allow the email address through, as most addresses are valid."
  [email-validation email-address]
  (log/with-context {:tag :email-validation
                     :email email-address}
    (cond
      (contains? disposable-email-blocklist (domain email-address))
      (do
        (log/info {:status :rejected
                   :source :local-blocklist})
        false)

      (disposable-email-via-user-check? email-validation email-address)
      (do
        (log/info {:status :rejected
                   :source :user-check})
        false)

      :else
      (do
        (log/info {:status :accepted})
        true))))
