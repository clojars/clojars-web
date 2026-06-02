(ns clojars.routes.common
  (:require
   [cheshire.core :as json]
   [clojars.log :as log]
   [clojure.string :as str]
   [malli.core :as m]
   [malli.error :as me]
   [malli.transform :as mt]
   [ring.util.response :as response])
  (:import
   (java.util
    Date)))

(defn request-details
  "Captures a map of audit details from the request."
  [{:as _request :keys [headers remote-addr]}]
  (let [{:strs [user-agent x-forwarded-for]} headers]
    {:remote-addr (or x-forwarded-for remote-addr)
     :timestamp   (Date.)
     :user-agent  user-agent}))

(defn malli-error-list
  [explanation]
  (when explanation
    (mapv
     (fn [[k messages]]
       (format "%s %s"
               (name k)
               (str/join " or " messages)))
     (me/humanize explanation))))

(defn reject-params
  [explanation]
  (log/warn {:tag         :invalid-params
             :explanation (pr-str explanation)})
  (-> {:errors (me/humanize explanation)}
      (json/encode)
      (response/bad-request)
      (response/content-type "application/json")))

(defn coerce-params
  ([schema request handler]
   (coerce-params schema request handler reject-params))
  ([schema {:as request :keys [params]} handler failure-handler]
   (let [coerced-or-failure (try
                              (m/coerce schema params mt/string-transformer)
                              (catch Exception e e))]
     (if (instance? Exception coerced-or-failure)
       (failure-handler (get-in (ex-data coerced-or-failure) [:data :explain]))
       (handler (assoc request :params coerced-or-failure))))))

(def non-empty-string-schema
  (m/schema [:string
             {:error/message {:en "should not be an empty string"}
              :min 1}]))

(def empty-string-schema
  (m/schema [:string
             {:error/message {:en "should be an empty string"}
              :max 0}]))
