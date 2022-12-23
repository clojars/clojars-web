(ns clojars.unit.web.common-test
  (:require
   [clojars.web.common :as common]
   [clojure.test :refer [deftest is]]
   [hiccup.util :refer [to-str]]))

(deftest jar-name-uses-shortest-unique-and-html-escape
  (is (= "group/artifact" (common/jar-name {:jar_name "artifact"
                                            :group_name "group"})))
  (is (= "artifact" (common/jar-name {:jar_name "artifact"
                                      :group_name "artifact"}))))

(deftest jar-url-uses-shortest-unique
  (is (= "/group/artifact" (common/jar-url {:jar_name "artifact" :group_name "group"})))
  (is (= "/artifact" (common/jar-url {:jar_name "artifact" :group_name "artifact"}))))

(deftest error-list-populates-errors-correctly
  (is (= nil (common/error-list nil)))
  (is (=
       [:div#notice.error [:strong "Blistering barnacles!"] "  Something's not shipshape:" [:ul '([:li "error"])]]
       (common/error-list ["error"]))))

(deftest user-link-works
  (is (= [:a {:href (java.net.URI. "/users/kiwi")} '("kiwi")]
         (common/user-link "kiwi"))))

(deftest group-link-works
  (is (= [:a {:href (java.net.URI. "/groups/artifruit")} '("artifruit")]
         (common/group-link "artifruit"))))

(letfn [(cook-content [v]
          (conj (vec (butlast v)) (to-str (last v))))]

  (deftest page-nav-renders-disabled-previous-page
    (is (=
         [:span.previous-page.disabled "&#8592; Previous"]
         (-> (common/page-nav 1 3) (get 1) cook-content))))

  (deftest page-nav-renders-active-previous-page
    (is (=
         [:a {:href (java.net.URI. "/projects?page=1")} "(1)"]
         (-> (common/page-nav 2 3) (get 2) cook-content))))

  (deftest page-nav-renders-disabled-next-page
    (is (=
         [:span.next-page.disabled "Next &#8594;"]
         (-> (common/page-nav 3 3) (last) cook-content))))

  (deftest page-nav-renders-active-next-page
    (is (=
         [:a.next-page {:href "/projects?page=3"} "Next &#8594;"]
         (-> (common/page-nav 2 3) (last) cook-content)))))

(deftest page-nav-renders-no-before-links
  (is (=
       [:em.current :a]
       (->> (-> (common/page-nav 1 3) (subvec 2 4)) (map first)))))

(deftest page-nav-renders-some-before-links
  (is (=
       [:em.current :a]
       (->> (-> (common/page-nav 2 3) (subvec 3 5)) (map first)))))

(deftest page-nav-renders-all-before-links
  (is (=
       [:a :a :em.current :a]
       (->> (-> (common/page-nav 5 10) (subvec 3 7)) (map first)))))

(deftest page-nav-renders-no-after-links
  (is (=
       [:em.current :span.next-page.disabled]
       (->> (-> (common/page-nav 3 3) (subvec 4 6)) (map first)))))

(deftest page-nav-renders-some-after-links
  (is (=
       [:a :a.next-page]
       (->> (-> (common/page-nav 3 4) (subvec 5 7)) (map first)))))

(deftest page-nav-renders-all-after-links
  (is (=
       [:a :a :a :a.next-page]
       (->> (-> (common/page-nav 3 10) (subvec 5 9)) (map first)))))

(deftest page-nav-handles-negative-page
  (is (=
       [:em.current :a :a :a.next-page]
       (->> (-> (common/page-nav -1 3) (subvec 2)) (map first)))))

(deftest page-nav-handles-nonexistent-page
  (is (=
       [:a :a :em.current :span.next-page.disabled]
       (->> (-> (common/page-nav 4 3) (subvec 2)) (map first)))))

(deftest page-description-on-first-page
  (is (=
       [[:b "1 - 20"] " of " [:b 80]]
       (-> (common/page-description 1 20 80) (subvec 3)))))

(deftest page-description-on-last-page-with-total-equal-to-upper-limit
  (is (=
       [[:b "61 - 80"] " of " [:b 80]]
       (-> (common/page-description 4 20 80) (subvec 3)))))

(deftest page-description-on-last-page-with-total-not-equal-to-upper-limit
  (is (=
       [[:b "61 - 75"] " of " [:b 75]]
       (-> (common/page-description 4 20 75) (subvec 3)))))

(deftest page-description-handles-negative-page
  (is (=
       [:b "1 - 20"]
       (-> (common/page-description -1 20 80) (get 3)))))

(deftest page-description-handles-nonexistent-page
  (is (=
       [:b "61 - 80"]
       (-> (common/page-description 15 20 80) (get 3)))))
