(ns clojars.hibp
  "Checks passwords against the Have I Been Pwned Pwned Passwords range API.
  Only the first 5 characters of the password's SHA-1 hash are sent (see
  https://haveibeenpwned.com/API/v3#SearchingPwnedPasswordsByRange)."
  (:require
   [buddy.core.codecs :as codecs]
   [buddy.core.hash :as hash]
   [clojars.http-client :as http]
   [clojars.log :as log]
   [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defprotocol PwnedPasswords
  (-pwned? [self password]
    "Returns true if `password` is known to have appeared in a breach."))

(def ^:private range-url "https://api.pwnedpasswords.com/range/")

;; Short, since this is called during login and registration. We fail open on
;; a timeout.
(def ^:private timeout-ms 3000)

(defn- sha1-hex
  [^String password]
  (-> (hash/sha1 password)
      (codecs/bytes->hex)
      (str/upper-case)))

(defn- suffix-in-range?
  "Returns true if `suffix` appears in the range `body` with a non-zero count.
  Padding entries (added via Add-Padding) have a count of 0."
  [body suffix]
  (boolean
   (some (fn [line]
           (let [[line-suffix cnt] (str/split (str/trim line) #":")]
             (and (= suffix line-suffix)
                  (some-> cnt parse-long pos?))))
         (str/split-lines body))))

(defrecord HttpPwnedPasswords []
  PwnedPasswords
  (-pwned? [_ password]
    (let [hashed (sha1-hex password)
          prefix (subs hashed 0 5)
          suffix (subs hashed 5)]
      (try
        (let [{:keys [body error status]} (http/request
                                           {:method  :get
                                            :url     (str range-url prefix)
                                            :timeout timeout-ms
                                            :as      :text
                                            :headers {"Add-Padding" "true"
                                                      "User-Agent"  "clojars-web"}})]
          (if (and (nil? error) (= 200 status))
            (suffix-in-range? body suffix)
            (do
              (log/warn {:tag    :hibp-request-failed
                         :status status
                         :error  error})
              false)))
        (catch Exception e
          (log/warn {:tag   :hibp-request-failed
                     :error e})
          false)))))

(defrecord MockPwnedPasswords [pwned-passwords]
  PwnedPasswords
  (-pwned? [_ password]
    (contains? pwned-passwords password)))

(defn pwned?
  "Returns true if `password` is known to be compromised. Fails open: returns
  false if the API can't be reached so an outage doesn't lock users out."
  [hibp password]
  (and (not (str/blank? password))
       (-pwned? hibp password)))

(defn new-hibp
  []
  (->HttpPwnedPasswords))

(defn new-mock-hibp
  "A mock that considers only `pwned-passwords` to be compromised."
  ([]
   (new-mock-hibp #{}))
  ([pwned-passwords]
   (->MockPwnedPasswords (set pwned-passwords))))
