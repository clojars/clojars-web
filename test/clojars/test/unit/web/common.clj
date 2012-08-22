(ns clojars.test.unit.web.common
  (:require [clojars.web.common :as common]
            [clojure.test :refer :all]))

(deftest jar-name-uses-shortest-unique-and-html-escape
  (is (= "group/artifact" (common/jar-name {:jar_name "artifact"
                                            :group_name "group"})))
  (is (= "artifact" (common/jar-name {:jar_name "artifact"
                                      :group_name "artifact"})))
  (is (= "&lt;/alert&gt;/&lt;alert&gt;" (common/jar-name {:jar_name "<alert>"
                                           :group_name "</alert>"}))))

(deftest jar-url-uses-shortest-unique
  (is (= "/group/artifact" (common/jar-url {:jar_name "artifact" :group_name "group"})))
  (is (= "/artifact" (common/jar-url {:jar_name "artifact" :group_name "artifact"}))))

(deftest error-list-populates-errors-correctly
  (is (= nil (common/error-list nil)))
  (is (=
        [:div {:class :error} [:strong "Blistering barnacles!"] "  Something's not shipshape:" [:ul '([:li "error"])]]
        (common/error-list ["error"]))))

(deftest user-link-works
  (is (= [:a {:href (java.net.URI. "/users/kiwi")} '("kiwi")]
         (common/user-link "kiwi"))))

(deftest group-link-works
  (is (= [:a {:href (java.net.URI. "/groups/artifruit")} '("artifruit")]
         (common/group-link "artifruit"))))

(deftest page-nav-renders-disabled-previous-page
  (is (=
        [:span {:class "previous_page disabled"} "&#8592; Previous"]
        (-> (common/page-nav 1 3) (get 2)))))

(deftest page-nav-renders-active-previous-page
  (is (=
        [:a {:href "/projects?page=1" :class "previous_page"} "&#8592; Previous"]
        (-> (common/page-nav 2 3) (get 2)))))

(deftest page-nav-renders-disabled-next-page
  (is (=
        [:span {:class "next_page disabled"} "Next &#8594"]
        (-> (common/page-nav 3 3) (last)))))

(deftest page-nav-renders-active-next-page
  (is (=
        [:a {:href "/projects?page=3" :class "next_page"} "Next &#8594"]
        (-> (common/page-nav 2 3) (last)))))
