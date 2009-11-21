(ns clojars.web.dashboard
  (:use clojars.web.common
        clojars.db
        compojure))

(defn index-page [account]
  (html-doc account nil
    "<h1>Coming soon!</h1>
        <p>Clojars.org will be a <strong>dead easy</strong> jar repository for
          open source <a href=\"http://clojure.org/\">Clojure</a> libraries.</p>
        <h2>But what about Maven?</h2>

        <p>Maven is not exactly <strong>simple</strong> and it's a <a
        href=\"http://maven.apache.org/guides/mini/guide-central-repository-upload.html\">hassle</a>
        to get things into the central repository.  Maven central also
        isn't suited for forking or personal projects.  Clojars is
        intended to make distributing code as easy as possibe for
        <strong>everyone</strong>.</p>
        <p>But <strong>don't worry</strong>, the plan is to expose
        things as a Maven and Ivy compatible repository so you can use
        them if you want to.</p>
        <h2>But I <em>hate</em> Maven!</h2>

        <p>I hope to support other tools&mdash;such as <a
        href=\"http://github.com/technomancy/leiningen\">Leiningen</a>"
    "&mdash;as well.</p>
        <h2>Isn't this just a Clojure clone of
          <a href=\"http://gemcutter.org/\">gemcutter.org</a>?</h2>
        <p>Why mess with a good idea? :-)</p>"))

(defn dashboard [account]
  (html-doc account "Dashboard"
    [:h1 (str "Dashboard (" (h account) ")")]
    [:h2 "Your jars"]
    (unordered-list (map jar-link (jars-by-user account)))
    (link-to "http://wiki.github.com/ato/clojars-web/pushing" "add new jar")
    [:h2 "Your groups"]
    (unordered-list (map group-link (find-groups account)))))
