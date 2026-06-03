(ns clojars.hibp
  "Have I Been Pwned password compromise detection.

  Uses the Pwned Passwords k-anonymity range API: we hash the password with
  SHA-1, send only the first 5 hex chars of the hash to the API, and check
  the returned suffix list locally. The password and full hash never leave
  the application.

  See https://haveibeenpwned.com/API/v3#PwnedPasswords"
  (:require
   [clj-http.client :as http]
   [clojars.log :as log]
   [clojure.string :as str]
   [digest])
  (:import
   (java.net SocketTimeoutException)))

(set! *warn-on-reflection* true)

(def ^:private api-url "https://api.pwnedpasswords.com/range/")
(def ^:private user-agent "clojars-web (https://github.com/clojars/clojars-web)")
(def ^:private request-timeout-ms 2000)

(defprotocol Hibp
  (-pwned-count
    [self password]
    "Returns the number of times `password` appears in the HIBP data set, or 0
    if the password is not present. Throws on network or parse errors so the
    caller can decide how to fail (typically: fail open)."))

(defn- sha1-hex
  ^String [^String s]
  (str/upper-case (digest/sha-1 s)))

(defn- parse-range-response
  [^String body suffix]
  (or (some (fn [line]
              (let [[line-suffix line-count] (str/split line #":")]
                (when (= line-suffix suffix)
                  (parse-long (str/trim line-count)))))
            (str/split-lines body))
      0))

(defrecord HttpHibp []
  Hibp
  (-pwned-count [_ password]
    (let [hash (sha1-hex password)
          prefix (subs hash 0 5)
          suffix (subs hash 5)
          {:keys [body status]}
          (http/get (str api-url prefix)
                    {:headers {"User-Agent" user-agent
                               "Add-Padding" "true"}
                     :socket-timeout request-timeout-ms
                     :connection-timeout request-timeout-ms
                     :throw-exceptions false})]
      (if (= 200 status)
        (parse-range-response body suffix)
        (throw (ex-info "Unexpected HIBP response"
                        {:status status}))))))

(defrecord MockHibp [pwned-passwords]
  Hibp
  (-pwned-count [_ password]
    (get @pwned-passwords password 0)))

(defn pwned?
  "Returns true if `password` is known to HIBP. Fails open: returns false if
  HIBP is unreachable or returns an unexpected response, so users aren't locked
  out by a third-party outage. Returns false for blank passwords."
  [hibp password]
  (cond
    (nil? hibp) false
    (str/blank? password) false
    :else
    (try
      (let [n (-pwned-count hibp password)]
        (when (pos? n)
          (log/info {:tag :hibp-pwned :count n}))
        (pos? n))
      (catch SocketTimeoutException e
        (log/warn {:tag :hibp-timeout :error (.getMessage e)})
        false)
      (catch Exception e
        (log/warn {:tag :hibp-error :error (.getMessage e)})
        false))))

(defn new-hibp
  "Production HIBP client that talks to the real HIBP API."
  []
  (->HttpHibp))

(defn new-mock-hibp
  "Test HIBP client. `pwned-passwords` is an atom holding a map of plaintext
  password -> breach count."
  [pwned-passwords]
  (->MockHibp pwned-passwords))
