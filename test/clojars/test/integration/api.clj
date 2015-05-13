(ns clojars.test.integration.api
  (:require [clj-http.lite.client :as client]
            [clojars.test.integration.steps :refer [register-as scp valid-ssh-key]]
            [clojars.test.test-helper :as help]
            [clojars.web :as web]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [kerodon.core :refer [session]]
            [cheshire.core :as json]))

(use-fixtures :once help/run-test-app #_(partial help/run-test-app :verbose))
(use-fixtures :each help/default-fixture)

(defn parse [format data]
  (case format
    :json (json/parse-string data keyword)
    data))

(defn get-api
  ([parts]
   (get-api :json parts))
  ([format parts]
   (-> (str "http://localhost:" help/test-port "/api/"
         (str/join "/" (map name parts)))
     (client/get {:accept format})
     (update-in [:body] (partial parse format)))))

(deftest an-api-test
  (-> (session web/clojars-app)
      (register-as "dantheman" "test@example.org" "password" valid-ssh-key))
  (scp valid-ssh-key "test.jar" "test-0.0.1/test.pom")
  (scp valid-ssh-key "test.jar" "test-0.0.2/test.pom")
  (scp valid-ssh-key "test.jar" "test-0.0.3-SNAPSHOT/test.pom")
  (scp valid-ssh-key "test.jar" "test-0.0.3-SNAPSHOT/test.pom")
  (-> (get-api [:groups "fake"])
    clojure.pprint/pprint)
  (-> (get-api [:artifacts "fake" "test"])
    clojure.pprint/pprint)
  (-> (get-api [:users "dantheman"])
    clojure.pprint/pprint)
  (is false))
