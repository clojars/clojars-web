(ns clojars.unit.credential-scan-test
  (:require
   [clojars.credential-scan :as credential-scan]
   [clojars.models.deploy-token :as deploy-token-m]
   [clojars.test-helper :refer [jar-with]]
   [clojure.test :refer [are deftest is]])
  (:import
   (java.io
    File)))

(def token (deploy-token-m/generate-deploy-token))

(defn- tmp-file
  ^File [suffix]
  (doto (File/createTempFile "credential-scan-test" suffix)
    (.deleteOnExit)))

(deftest find-credential
  (are [expected text] (= expected (:credential-type (credential-scan/find-credential text)))
    :deploy-token (format "{:repositories [[\"clojars\" {:password \"%s\"}]]}" token)
    :deploy-token (format "<settings><token>%s</token></settings>" token)
    :password     "{:deploy-repositories [[\"clojars\" {:username \"me\" :password \"hunter2\"}]]}"
    :password     "{:password\n  \"hunter2\"}"
    :password     "<server><password>hunter2</password></server>"
    nil           "{:deploy-repositories [[\"clojars\" {:username :env :password :env}]]}"
    nil           "{:password :env/clojars_token}"
    nil           "{:password \"\"}"
    nil           "{:db-password \"hunter2\"}"
    nil           "<server><password>${env.CLOJARS_TOKEN}</password></server>"
    nil           "<password></password>"
    nil           "(defproject foo \"1.0.0\" :license {:name \"EPL\"})"))

(deftest find-credential-returns-every-deploy-token
  (let [other-token (deploy-token-m/generate-deploy-token)]
    (is (= {:credential-type :deploy-token
            :tokens          #{token other-token}}
           (credential-scan/find-credential
            (format "{:password \"hunter2\" :repositories [[\"a\" {:password \"%s\"}] [\"b\" {:password \"%s\"}]]}"
                    token other-token))))))

(deftest scan-artifact-finds-credentials-in-jar-build-files
  (is (= {:entry           "META-INF/leiningen/foo/foo/project.clj"
          :credential-type :password}
         (credential-scan/scan-artifact
          (jar-with {"foo/core.clj"                            "(ns foo.core)"
                     "META-INF/leiningen/foo/foo/project.clj" "(defproject foo \"1.0.0\" :deploy-repositories [[\"clojars\" {:password \"hunter2\"}]])"}))))
  (is (= {:entry           "deps.edn"
          :credential-type :deploy-token
          :tokens          #{token}}
         (credential-scan/scan-artifact
          (jar-with {"deps.edn" (format "{:aliases {:deploy {:exec-args {:password \"%s\"}}}}" token)})))))

(deftest scan-artifact-ignores-source-files-in-jars
  (is (nil? (credential-scan/scan-artifact
             (jar-with {"foo/core.clj" "(def config {:password \"hunter2\"})"
                        "project.clj"  "(defproject foo \"1.0.0\")"})))))

(deftest scan-artifact-scans-poms
  (let [pom (tmp-file ".pom")]
    (spit pom "<project><properties><password>hunter2</password></properties></project>")
    (is (= {:credential-type :password} (credential-scan/scan-artifact pom)))
    (spit pom "<project><artifactId>foo</artifactId></project>")
    (is (nil? (credential-scan/scan-artifact pom)))))

(deftest scan-artifact-ignores-jars-that-are-not-zips
  (let [jar (tmp-file ".jar")]
    (spit jar "bad jar")
    (is (nil? (credential-scan/scan-artifact jar)))))
