(ns clojars.web.safe-hiccup
  "Hiccup requires explicit (h ..) calls in order to preven XSS.  This
does some monkey patching to automatically escape strings."
  (:require [hiccup.core :refer [html]]
            [hiccup.page :refer [doctype]]
            [hiccup.compiler :refer [HtmlRenderer]]
            [hiccup.util :refer [escape-html ToString]]
            [ring.middleware.anti-forgery :refer [ *anti-forgery-token*]]
            [hiccup.form :as form]))

(deftype RawString [s]
  HtmlRenderer
  (render-html [_] s)
  ToString
  (to-str [_] s))

(defn raw [s]
  (RawString. s))

(extend-protocol HtmlRenderer
  Object
  (render-html [s] (escape-html s)))

(defmacro html5
  "Create a HTML5 document with the supplied contents."
  [options & contents]
  (if-not (map? options)
    `(html5 {} ~options ~@contents)
    (if (options :xml?)
      `(let [options# ~options]
         (html {:mode :xml}
           (raw (xml-declaration (options# :encoding "UTF-8")))
           (raw (doctype :html5))
           (xhtml-tag (options# :lang) ~@contents)))
      `(let [options# ~options]
         (html {:mode :html}
           (raw (doctype :html5))
           [:html {:lang (options# :lang)} ~@contents])))))

(defmacro form-to
  "Create a form that points to a particular method and route.
e.g. (form-to [:put \"/post\"]
...)"
  [method-action & body]
  `(let [[method# action#] ~method-action]
     (if (contains? #{:head :get} method#)
       (form/form-to [method# action#] ~@body)
       (form/form-to [method# action#]
                     (conj
                      (form/hidden-field "__anti-forgery-token"
                                         *anti-forgery-token*)
                      ~@body)))))
