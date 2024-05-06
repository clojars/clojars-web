(ns clojars.web.helpers
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))

(defn public-resource-exists?
  "Takes a path and checks whether the resource exists under the public directory
  at that path."
  [path]
  (some-> (str "public" path)
          (io/resource)))

(defn srcset-part
  "Creates a srcset part, e.g.
  \"images/rht-logo@2x.png 2x\"
  Returns nil if the file referenced does not exist"
  [base extension scale]
  (let [retina-path (str base "@" scale extension)]
    (when (and retina-path (public-resource-exists? retina-path))
      (str retina-path " " scale))))

(defn retinized-image
  "Creates an img tag with support for 2x and 3x retina images.
  Will check if the retina images exist before adding them to srcset.
  Throws if the src image does not exist."
  [^String src alt]
  (assert (= (first src) \/) (format "src %s must start with a /" src))
  (assert (public-resource-exists? src)
          (format "File %s does not exist" (str "public" src)))

  (let [last-period (.lastIndexOf src ".")
        base (subs src 0 last-period)
        extension (subs src last-period (count src))]
    [:img {:src    src
           :alt    alt
           ;; If 1x is not provided in srcset, then browsers will default to src as the 1x image
           :srcset (->> (filter identity [(srcset-part base extension "2x")
                                          (srcset-part base extension "3x")])
                        (str/join ", "))}]))
