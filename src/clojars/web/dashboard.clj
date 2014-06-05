(ns clojars.web.dashboard
  (:require [clojars.web.common :refer [html-doc jar-link group-link tag]]
            [clojars.db :refer [jars-by-username find-groupnames recent-jars]]
            [hiccup.element :refer [unordered-list link-to]]))

(defn recent-jar [title description]
  [:li.recent-jar
   [:div.jar
    [:h3.recent-title
     [:a {:href "#"} title]]
    [:p.description description]]])

(defn index-page [account]
  (html-doc account nil
    [:div {:class "useit-lein"}
     [:h3 "Push with Leiningen"]
     [:div {:class "lein"}
      [:pre
       (tag "$") " lein pom\n"
       (tag "$") " scp pom.xml mylib.jar clojars@clojars.org:"]]]
    [:div {:class "useit-maven"}
     [:h3 "Maven Repository"]
     [:div {:class "maven"}
      [:pre
       (tag "<repository>\n")
       (tag "  <id>") "clojars.org" (tag "</id>\n")
       (tag "  <url>") "http://clojars.org/repo" (tag "</url>\n")
       (tag "</repository>")]]]
    [:h2 {:class "recent"} "Recently pushed projects"]
    [:ul
     (map #(apply recent-jar %)
          [["foo" "bar"]
           ["bar" "baz"]
           ["def" "ghi"]
           ["jkl" "mno"]
           ["pqr" "stu"]
           ["vwx" "yz1"]])]
    ;; TODO: Bring me back.
    ;; (unordered-list (map jar-link (recent-jars)))
    ))

(defn dashboard [account]
  (html-doc account "Dashboard"
    [:h1 (str "Dashboard (" account ")")]
    [:h2 "Your projects"]
    (unordered-list (map jar-link (jars-by-username account)))
    (link-to "http://wiki.github.com/ato/clojars-web/pushing" "add new project")
    [:h2 "Your groups"]
    (unordered-list (map group-link (find-groupnames account)))))
