(ns clojars.test.unit.web.helpers
  (:require [clojure.test :refer :all]
            [clojars.web.helpers :as helpers]))

(deftest resource-exists?
  (is (helpers/public-resource-exists? "/test-resource"))
  (is (not (helpers/public-resource-exists? "/nonexistent-resource"))))

(deftest srcset-part
  (is (= "/img1@2x.png 2x"
         (helpers/srcset-part "/img1" ".png" "2x")))
  (is (= "/img1@3x.png 3x"
         (helpers/srcset-part "/img1" ".png" "3x")))
  (is (nil? (helpers/srcset-part "/img2" ".png" "2x"))))

(deftest retinized-imaged
  (is (= [:img
          {:alt    "Image 1"
           :src    "/img1.png"
           :srcset "/img1@2x.png 2x, /img1@3x.png 3x"}]
         (helpers/retinized-image "/img1.png" "Image 1")))
  (is (= [:img
          {:alt    "Image 2"
           :src    "/img2.png"
           :srcset "/img2@3x.png 3x"}]
         (helpers/retinized-image "/img2.png" "Image 2")))
  (is (= [:img
          {:alt    "Image 3"
           :src    "/img3.jpeg"
           :srcset ""}]
         (helpers/retinized-image "/img3.jpeg" "Image 3")))
  (is (thrown? AssertionError (helpers/retinized-image "/img-not-exist.png" "Nope")))
  (is (thrown? AssertionError (helpers/retinized-image "img1.png" "Image 1"))))
