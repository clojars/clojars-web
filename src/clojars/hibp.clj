(ns clojars.hibp
  "Checks passwords against the Have I Been Pwned Pwned Passwords range API.
  Only the first 5 characters of the password's SHA-1 hash are sent (see
  https://haveibeenpwned.com/API/v3#SearchingPwnedPasswordsByRange)."
  (:require
   [buddy.core.codecs :as codecs]
   [buddy.core.hash :as hash]
   [clojars.remote-service :refer [defendpoint]]
   [clojure.string :as str]))

(set! *warn-on-reflection* true)

;; Short, since this is called during login and registration. We fail open on
;; a timeout.
(def ^:private timeout-ms 3000)

(defendpoint -password-range
  [_client hash-prefix]
  {:method        :get
   :url           (str "https://api.pwnedpasswords.com/range/" hash-prefix)
   :url-pattern   "https://api.pwnedpasswords.com/range/{hash_prefix}"
   :headers       {"Add-Padding" "true"}
   :timeout       timeout-ms
   :response-type :text})

(defn- sha1-hex
  [^String password]
  (-> (hash/sha1 password)
      (codecs/bytes->hex)
      (str/upper-case)))

(defn- suffix-in-range?
  "Returns true if `suffix` appears in the range `body`, whose lines are
  `SUFFIX:COUNT`."
  [body suffix]
  (boolean
   (some #(= suffix (first (str/split (str/trim %) #":")))
         (some-> body str/split-lines))))

(defn pwned?
  "Returns true if `password` is known to be compromised. Fails open: returns
  false if the API can't be reached so an outage doesn't lock users out."
  [hibp password]
  (and (not (str/blank? password))
       (let [hashed (sha1-hex password)]
         (try
           (suffix-in-range? (-password-range hibp (subs hashed 0 5))
                             (subs hashed 5))
           (catch Exception _
             false)))))
