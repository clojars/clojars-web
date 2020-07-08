(ns clojars.integration.uploads-test
  (:require
   [cemerick.pomegranate.aether :as aether]
   [clj-http.client :as client]
   [clojars.config :refer [config]]
   [clojars.db :as db]
   [clojars.file-utils :as fu]
   [clojars.integration.steps :refer [create-deploy-token login-as register-as]]
   [clojars.s3 :as s3]
   [clojars.test-helper :as help]
   [clojars.web.common :as common]
   [clojure.data.codec.base64 :as base64]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [are deftest is testing use-fixtures]]
   [kerodon.core :refer [fill-in follow press session visit within]]
   [kerodon.test :refer [has status? text?]]
   [net.cgrand.enlive-html :as enlive]))

(use-fixtures :each
  help/default-fixture
  help/with-local-repos
  help/run-test-app)

(defn repo-url []
  (str "http://localhost:" help/test-port "/repo"))

(defn tmp-file [f name]
  (let [tmp-f (doto (io/file help/tmp-dir name)
                (.deleteOnExit))]
    (io/copy f tmp-f)
    tmp-f))

(defn tmp-checksum-file [f type]
  (doto (fu/create-checksum-file f type)
    .deleteOnExit))

(deftest user-can-register-and-deploy
  (-> (session (help/app-from-system))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app-from-system)) "dantheman" "password" "testing")
        now (db/get-time)]
    (with-redefs [db/get-time (constantly now)]
      (aether/deploy
       :coordinates '[org.clojars.dantheman/test "0.0.1"]
       :jar-file (io/file (io/resource "test.jar"))
       :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.1/test.pom"))
                                   {:groupId "org.clojars.dantheman"})
       :repository {"test" {:url (repo-url)
                            :username "dantheman"
                            :password token}}
       :local-repo help/local-repo))

    (let [suffixes ["jar" "jar.md5" "jar.sha1" "pom" "pom.md5" "pom.sha1"]
          base-path "org/clojars/dantheman/test/"
          repo-bucket (:repo-bucket help/system)
          repo (:repo (config))]
      (is (.exists (io/file repo base-path "maven-metadata.xml")))
      (is (s3/object-exists? repo-bucket (str base-path "maven-metadata.xml")))
      (is (= 6 (count (.list (io/file repo base-path "0.0.1")))))
      (doseq [suffix suffixes]
        (is (.exists (io/file repo base-path "0.0.1" (str "test-0.0.1." suffix))))
        (is (s3/object-exists? repo-bucket (str base-path "0.0.1/test-0.0.1." suffix)))))

    (-> (session (help/app-from-system))
        (visit "/groups/org.clojars.dantheman")
        (has (status? 200))
        (within [:#content-wrapper
                 [:ul enlive/last-of-type]
                 [:li enlive/only-child]
                 :a]
                (has (text? "dantheman")))
        (follow "org.clojars.dantheman/test")
        (has (status? 200))
        (within [:#jar-sidebar :li.homepage :a]
                (has (text? "https://example.org"))))
    (-> (session (help/app-from-system))
        (visit "/")
        (fill-in [:#search] "test")
        (press [:#search-button])
        (within [:div.result]
                (has (text? "org.clojars.dantheman/test 0.0.1"))))
    (-> (session (help/app-from-system))
        (login-as "dantheman" "password")
        (visit "/tokens")
        (within [:td.last-used]
                (has (text? (common/simple-date now)))))))

(deftest deploying-with-a-scoped-token
  (-> (session (help/app-from-system))
      (register-as "dantheman" "test@example.org" "password"))

  (let [unscoped-token (create-deploy-token
                        (session (help/app-from-system)) "dantheman" "password" "unscoped")
        base-path      "org/clojars/dantheman/test/"
        repo           (:repo (config))]

    ;; setup groups using unscoped token
    (aether/deploy
     :coordinates '[org.clojars.dantheman/test "0.0.1"]
     :jar-file (io/file (io/resource "test.jar"))
     :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.1/test.pom"))
                                 {:groupId "org.clojars.dantheman"})
     :repository {"test" {:url      (repo-url)
                          :username "dantheman"
                          :password unscoped-token}}
     :local-repo help/local-repo)

    (aether/deploy
     :coordinates '[org.dantheman/test "0.0.1"]
     :jar-file (io/file (io/resource "test.jar"))
     :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.1/test.pom"))
                                 {:groupId "org.dantheman"})
     :repository {"test" {:url      (repo-url)
                          :username "dantheman"
                          :password unscoped-token}}
     :local-repo help/local-repo)

    (testing "with a group-scoped token"
      (let [group-scoped-token (create-deploy-token
                                (session (help/app-from-system)) "dantheman" "password" "group-scoped"
                                "org.clojars.dantheman")]

        (aether/deploy
         :coordinates '[org.clojars.dantheman/test "0.0.2"]
         :jar-file (io/file (io/resource "test.jar"))
         :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.1/test.pom"))
                                     {:groupId "org.clojars.dantheman"
                                      :version "0.0.2"})
         :repository {"test" {:url      (repo-url)
                              :username "dantheman"
                              :password group-scoped-token}}
         :local-repo help/local-repo)

        (is (= 6 (count (.list (io/file repo base-path "0.0.2")))))

        (is (thrown-with-msg?
             org.sonatype.aether.deployment.DeploymentException
             #"ReasonPhrase:Forbidden - The provided token's scope doesn't allow deploying this artifact"
             (aether/deploy
              :coordinates '[org.dantheman/test "0.0.2"]
              :jar-file (io/file (io/resource "test.jar"))
              :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.1/test.pom"))
                                          {:groupId "org.dantheman"
                                           :version "0.0.2"})
              :repository {"test" {:url      (repo-url)
                                   :username "dantheman"
                                   :password group-scoped-token}}
              :local-repo help/local-repo)))))

    (testing "with an artifact-scoped token"
      (let [artifact-scoped-token (create-deploy-token
                                   (session (help/app-from-system)) "dantheman" "password" "artifact-scoped"
                                   "org.clojars.dantheman/test")]

        (aether/deploy
         :coordinates '[org.clojars.dantheman/test "0.0.3"]
         :jar-file (io/file (io/resource "test.jar"))
         :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.1/test.pom"))
                                     {:groupId "org.clojars.dantheman"
                                      :version "0.0.3"})
         :repository {"test" {:url      (repo-url)
                              :username "dantheman"
                              :password artifact-scoped-token}}
         :local-repo help/local-repo)

        (is (= 6 (count (.list (io/file repo base-path "0.0.3")))))

        (is (thrown-with-msg?
             org.sonatype.aether.deployment.DeploymentException
             #"ReasonPhrase:Forbidden - The provided token's scope doesn't allow deploying this artifact"
             (aether/deploy
              :coordinates '[org.clojars.dantheman/test2 "0.0.1"]
              :jar-file (io/file (io/resource "test.jar"))
              :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.1/test.pom"))
                                          {:groupId "org.clojars.dantheman"
                                           :artifactId "test2"})
              :repository {"test" {:url      (repo-url)
                                   :username "dantheman"
                                   :password artifact-scoped-token}}
              :local-repo help/local-repo)))))))

(deftest user-cannot-deploy-with-disabled-token
  (-> (session (help/app-from-system))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app-from-system)) "dantheman" "password" "testing")
        db (:db (config))
        db-token (first (db/find-user-tokens-by-username db "dantheman"))]
    (db/disable-deploy-token db (:id db-token))
    (is (thrown-with-msg?
         org.sonatype.aether.deployment.DeploymentException
         #"401, ReasonPhrase:Unauthorized"
         (aether/deploy
          :coordinates '[org.clojars.dantheman/test "0.0.1"]
          :jar-file (io/file (io/resource "test.jar"))
          :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.1/test.pom"))
                                      {:groupId "org.clojars.dantheman"})
          :repository {"test" {:url (repo-url)
                               :username "dantheman"
                               :password token}}
          :local-repo help/local-repo)))))

(deftest user-can-deploy-artifacts-after-maven-metadata
  (-> (session (help/app-from-system))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app-from-system)) "dantheman" "password" "testing")
        add-checksums (partial mapcat (fn [[f no-version?]]
                                        [[f no-version?]
                                         [(tmp-checksum-file f :md5) no-version?]
                                         [(tmp-checksum-file f :sha1) no-version?]]))
        files (add-checksums [[(tmp-file
                                (io/file (io/resource "test.jar")) "test-0.0.1.jar")]
                              [(tmp-file
                                (help/rewrite-pom (io/file (io/resource "test-0.0.1/test.pom"))
                                                  {:groupId "org.clojars.dantheman"})
                                "test-0.0.1.pom")]
                              [(tmp-file
                                (io/file (io/resource "test-0.0.1/maven-metadata.xml"))
                                "maven-metadata.xml")
                               :no-version]
                              ;; maven 3.5 will upload maven-metadata.xml twice when there are classified artifacts
                              ;; see https://github.com/clojars/clojars-web/issues/640
                              [(tmp-file
                                (io/file (io/resource "test-0.0.1/maven-metadata.xml"))
                                "maven-metadata.xml")
                               :no-version]
                              [(tmp-file
                                (io/file (io/resource "test.jar")) "test-sources-0.0.1.jar")]])]
    ;; we use clj-http here instead of aether to have control over the
    ;; order the files are uploaded
    (binding [clj-http.core/*cookie-store* (clj-http.cookies/cookie-store)]
      (doseq [[f no-version?] files]
        (client/put (format "%s/org/clojars/dantheman/test/%s%s"
                            (repo-url)
                            (if no-version? "" "0.0.1/")
                            (.getName f))
                    {:body f
                     :basic-auth ["dantheman" token]})))

    (let [base-path "org/clojars/dantheman/test/"
          repo-bucket (:repo-bucket help/system)
          repo (:repo (config))]
      (doseq [[f no-version?] files]
        (let [fname (.getName f)
              base-path' (if no-version? base-path (str base-path "0.0.1/"))]
          (is (.exists (io/file repo base-path' fname)))
          (is (s3/object-exists? repo-bucket (str base-path' fname))))))))

(deftest user-can-deploy-to-new-group
  (-> (session (help/app-from-system))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app-from-system)) "dantheman" "password" "testing")]
    (aether/deploy
     :coordinates '[fake/test "0.0.1"]
     :jar-file (io/file (io/resource "test.jar"))
     :pom-file (io/file (io/resource "test-0.0.1/test.pom"))
     :repository {"test" {:url (repo-url)
                          :username "dantheman"
                          :password token}}
     :local-repo help/local-repo))
  (-> (session (help/app-from-system))
      (visit "/groups/fake")
      (has (status? 200))
      (within [:#content-wrapper
               [:ul enlive/last-of-type]
               [:li enlive/only-child]
               :a]
              (has (text? "dantheman")))
      (follow "fake/test")
      (has (status? 200))
      (within [:#jar-sidebar :li.homepage :a]
              (has (text? "https://example.org")))))

(deftest user-cannot-deploy-to-groups-without-permission
  (-> (session (help/app-from-system))
      (register-as "dantheman" "test@example.org" "password"))
  (-> (session (help/app-from-system))
      (register-as "fixture" "fixture@example.org" "password"))
  (let [token (create-deploy-token (session (help/app-from-system)) "dantheman" "password" "testing")]
    (is (thrown-with-msg? org.sonatype.aether.deployment.DeploymentException
                          #"Forbidden - You don't have access to the 'org\.clojars\.fixture' group\.$"
                          (aether/deploy
                           :coordinates '[org.clojars.fixture/test "0.0.1"]
                           :jar-file (io/file (io/resource "test.jar"))
                           :pom-file (io/file (io/resource "test-0.0.1/test.pom"))
                           :repository {"test" {:url (repo-url)
                                                :username "dantheman"
                                                :password token}}
                           :local-repo help/local-repo)))))

(deftest user-can-deploy-to-group-when-not-admin
  (-> (session (help/app-from-system))
      (register-as "dantheman" "test@example.org" "password"))
  (-> (session (help/app-from-system))
      (register-as "fixture" "fixture@example.org" "password")
      (visit "/groups/org.clojars.fixture")
      (fill-in [:#username] "dantheman")
      (press "Add Member"))
  (let [token (create-deploy-token (session (help/app-from-system)) "dantheman" "password" "testing")]
    (aether/deploy
     :coordinates '[org.clojars.fixture/test "0.0.1"]
     :jar-file (io/file (io/resource "test.jar"))
     :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.1/test.pom"))
                                 {:groupId "org.clojars.fixture"})
     :repository {"test" {:url (repo-url)
                          :username "dantheman"
                          :password token}}
     :local-repo help/local-repo)))

(deftest user-cannot-deploy-to-reserved-groups
  (-> (session (help/app-from-system))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app-from-system)) "dantheman" "password" "testing")]
    (is (thrown-with-msg? org.sonatype.aether.deployment.DeploymentException
                          #"Forbidden - The group name 'dashboard' is reserved.$"
                          (aether/deploy
                           :coordinates '[dashboard/test "0.0.1"]
                           :jar-file (io/file (io/resource "test.jar"))
                           :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.1/test.pom"))
                                                       {:groupId "dashboard"})
                           :repository {"test" {:url (repo-url)
                                                :username "dantheman"
                                                :password token}}
                           :local-repo help/local-repo)))))


(deftest user-cannot-redeploy
  (-> (session (help/app-from-system))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app-from-system)) "dantheman" "password" "testing")]
    (aether/deploy
     :coordinates '[fake/test "0.0.1"]
     :jar-file (io/file (io/resource "test.jar"))
     :pom-file (io/file (io/resource "test-0.0.1/test.pom"))
     :repository {"test" {:url (repo-url)
                          :username "dantheman"
                          :password token}}
     :local-repo help/local-repo)
    (is (thrown-with-msg?
         org.sonatype.aether.deployment.DeploymentException
         #"Forbidden - redeploying non-snapshots"
         (aether/deploy
          :coordinates '[fake/test "0.0.1"]
          :jar-file (io/file (io/resource "test.jar"))
          :pom-file (io/file (io/resource "test-0.0.1/test.pom"))
          :repository {"test" {:url (repo-url)
                               :username "dantheman"
                               :password token}}
          :local-repo help/local-repo)))))

(deftest deploy-cannot-shadow-central
  (-> (session (help/app-from-system))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app-from-system)) "dantheman" "password" "testing")]
    (is (thrown-with-msg?
         org.sonatype.aether.deployment.DeploymentException
         #"Forbidden - shadowing Maven Central"
         (aether/deploy
          :coordinates '[org.tcrawley/dynapath "0.0.1"]
          :jar-file (io/file (io/resource "test.jar"))
          :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.1/test.pom"))
                                      {:groupId "org.tcrawley"
                                       :artifactId "dynapath"})
          :repository {"test" {:url (repo-url)
                               :username "dantheman"
                               :password token}}
          :local-repo help/local-repo)))))

(deftest deploy-cannot-shadow-central-unless-allowlisted
  (-> (session (help/app-from-system))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app-from-system)) "dantheman" "password" "testing")]
    (aether/deploy
     :coordinates '[net.mikera/clojure-pom "0.0.1"]
     :jar-file (io/file (io/resource "test.jar"))
     :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.1/test.pom"))
                                 {:groupId "net.mikera"
                                  :artifactId "clojure-pom"})
     :repository {"test" {:url (repo-url)
                          :username "dantheman"
                          :password token}}
     :local-repo help/local-repo)))

(deftest user-can-redeploy-snapshots
  (-> (session (help/app-from-system))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app-from-system)) "dantheman" "password" "testing")]
    (aether/deploy
     :coordinates '[fake/test "0.0.3-SNAPSHOT"]
     :jar-file (io/file (io/resource "test.jar"))
     :pom-file (io/file (io/resource "test-0.0.3-SNAPSHOT/test.pom"))
     :repository {"test" {:url (repo-url)
                          :username "dantheman"
                          :password token}}
     :local-repo help/local-repo)
    (aether/deploy
     :coordinates '[fake/test "0.0.3-SNAPSHOT"]
     :jar-file (io/file (io/resource "test.jar"))
     :pom-file (io/file (io/resource "test-0.0.3-SNAPSHOT/test.pom"))
     :repository {"test" {:url (repo-url)
                          :username "dantheman"
                          :password token}}
     :local-repo help/local-repo)))

(deftest user-can-deploy-snapshot-with-dot
  (-> (session (help/app-from-system))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app-from-system)) "dantheman" "password" "testing")]
    (aether/deploy
     :coordinates '[org.clojars.dantheman/test.thing "0.0.3-SNAPSHOT"]
     :jar-file (io/file (io/resource "test.jar"))
     :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.3-SNAPSHOT/test.pom"))
                                 {:groupId "org.clojars.dantheman"
                                  :artifactId "test.thing"})
     :repository {"test" {:url (repo-url)
                          :username "dantheman"
                          :password token}}
     :local-repo help/local-repo)))

(deftest snapshot-deploys-preserve-timestamp-version
  (-> (session (help/app-from-system))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app-from-system)) "dantheman" "password" "testing")
        timestamped-jar (atom nil)]
    (aether/deploy
      :coordinates '[fake/test "0.0.3-SNAPSHOT"]
      :jar-file (io/file (io/resource "test.jar"))
      :pom-file (io/file (io/resource "test-0.0.3-SNAPSHOT/test.pom"))
      :repository {"test" {:url      (repo-url)
                           :username "dantheman"
                           :password token}}
      :local-repo help/local-repo
      :transfer-listener #(when-let [name (get-in % [:resource :name])]
                            (when (.endsWith name ".jar")
                              (reset! timestamped-jar name))))
    (is @timestamped-jar)
    (is (.exists (io/file (:repo (config)) @timestamped-jar)))))

(deftest deploys-sharing-the-same-session-work
  (-> (session (help/app-from-system))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app-from-system)) "dantheman" "password" "testing")
        add-checksums (partial mapcat (fn [[f no-version?]]
                                        [[f no-version?]
                                         [(tmp-checksum-file f :md5) no-version?]
                                         [(tmp-checksum-file f :sha1) no-version?]]))
        files (add-checksums [[(tmp-file
                                 (io/file (io/resource "test.jar")) "test-0.0.3-%.jar")]
                              [(tmp-file
                                 (help/rewrite-pom (io/file (io/resource "test-0.0.3-SNAPSHOT/test.pom"))
                                                   {:groupId "org.clojars.dantheman"})
                                 "test-0.0.3-%.pom")]
                              [(tmp-file
                                 (io/file (io/resource "test-0.0.1/maven-metadata.xml"))
                                 "maven-metadata.xml")
                               :no-version]])
        versioned-name #(str/replace (.getName %1) #"%" %2)]
    ;; we use clj-http here instead of aether to have control over the
    ;; cookies
    (binding [clj-http.core/*cookie-store* (clj-http.cookies/cookie-store)]
      (doseq [[f no-version?] files]
        (client/put (format "%s/org/clojars/dantheman/test/%s%s"
                            (repo-url)
                            (if no-version? "" "0.0.3-SNAPSHOT/")
                            (versioned-name f "20170505.125640-1"))
                    {:body f
                     :throw-entire-message? true
                     :basic-auth ["dantheman" token]}))

      (doseq [[f no-version?] files]
        (client/put (format "%s/org/clojars/dantheman/test/%s%s"
                            (repo-url)
                            (if no-version? "" "0.0.3-SNAPSHOT/")
                            (versioned-name f "20170505.125655-99"))
                    {:body f
                     :throw-entire-message? true
                     :basic-auth ["dantheman" token]})))))

(deftest user-can-deploy-with-classifiers
  (-> (session (help/app-from-system))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app-from-system)) "dantheman" "password" "testing")]
    (aether/deploy
     :coordinates '[fake/test "0.0.1"]
     :artifact-map {[:extension "jar"] (io/file (io/resource "test.jar"))
                    [:classifier "test" :extension "jar"] (io/file (io/resource "test.jar"))
                    [:extension "pom"] (io/file (io/resource "test-0.0.1/test.pom"))}
     :repository {"test" {:url (repo-url)
                          :username "dantheman"
                          :password token}}
     :local-repo help/local-repo))
  (are [ext] (.exists (io/file (:repo (config)) "fake" "test" "0.0.1"
                               (format "test-0.0.1%s" ext)))
    ".pom" ".jar" "-test.jar"))

(deftest user-can-deploy-snapshot-with-classifiers
  (-> (session (help/app-from-system))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app-from-system)) "dantheman" "password" "testing")]
    (aether/deploy
     :coordinates '[fake/test "0.0.3-SNAPSHOT"]
     :artifact-map {[:extension "jar"] (io/file (io/resource "test.jar"))
                    [:classifier "test" :extension "jar"] (io/file (io/resource "test.jar"))
                    [:extension "pom"] (io/file (io/resource "test-0.0.3-SNAPSHOT/test.pom"))}
     :repository {"test" {:url (repo-url)
                          :username "dantheman"
                          :password token}}
     :local-repo help/local-repo))
  ;; we can't look for files directly since the version will be timestamped
  (is (= 3 (->> (file-seq (io/file (:repo (config)) "fake" "test" "0.0.3-SNAPSHOT"))
                (filter (memfn isFile))
                (filter #(re-find #"(pom|jar|-test\.jar)$" (.getName %)))
                count))))

(deftest user-can-deploy-with-signatures
  (-> (session (help/app-from-system))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app-from-system)) "dantheman" "password" "testing")
        pom (io/file (io/resource "test-0.0.1/test.pom"))]
    (aether/deploy
      :coordinates '[fake/test "0.0.1"]
      :artifact-map {[:extension "jar"] (io/file (io/resource "test.jar"))
                     [:extension "pom"] pom
                     ;; any content will do since we don't validate signatures
                     [:extension "jar.asc"] pom
                     [:extension "pom.asc"] pom}
      :repository {"test" {:url (repo-url)
                           :username "dantheman"
                           :password token}}
      :local-repo help/local-repo)))

(deftest missing-signature-fails-the-deploy
  (-> (session (help/app-from-system))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app-from-system)) "dantheman" "password" "testing")
        pom (io/file (io/resource "test-0.0.1/test.pom"))]
    (is (thrown-with-msg? org.sonatype.aether.deployment.DeploymentException
                          #"test-0.0.1.pom has no signature"
                          (aether/deploy
                            :coordinates '[fake/test "0.0.1"]
                            :artifact-map {[:extension "jar"] (io/file (io/resource "test.jar"))
                                           [:extension "pom"] pom
                                           ;; any content will do since we don't validate signatures
                                           [:extension "jar.asc"] pom}
                            :repository {"test" {:url (repo-url)
                                                 :username "dantheman"
                                                 :password token}}
                            :local-repo help/local-repo)))))

(deftest anonymous-cannot-deploy
  (is (thrown-with-msg?
       org.sonatype.aether.deployment.DeploymentException
       #"Unauthorized"
       (aether/deploy
        :coordinates '[fake/test "1.0.0"]
        :jar-file (io/file (io/resource "test.jar"))
        :pom-file (io/file (io/resource "test-0.0.1/test.pom"))
        :repository {"test" {:url (repo-url)}}
        :local-repo help/local-repo))))

(deftest bad-login-cannot-deploy
  (is (thrown-with-msg?
       org.sonatype.aether.deployment.DeploymentException
       #"Unauthorized - a deploy token is required to deploy"
       (aether/deploy
        :coordinates '[fake/test "1.0.0"]
        :jar-file (io/file (io/resource "test.jar"))
        :pom-file (io/file (io/resource "test-0.0.1/test.pom"))
        :repository {"test" {:url (repo-url)
                             :username "guest"
                             :password "password"}}
        :local-repo help/local-repo))))

(deftest deploy-requires-path-to-match-pom
  (-> (session (help/app-from-system))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app-from-system)) "dantheman" "password" "testing")]
    (is (thrown-with-msg?
         org.sonatype.aether.deployment.DeploymentException
         #"Forbidden - the group in the pom \(fake\) does not match the group you are deploying to \(flake\)"
         (aether/deploy
          :coordinates '[flake/test "0.0.1"]
          :jar-file (io/file (io/resource "test.jar"))
          :pom-file (io/file (io/resource "test-0.0.1/test.pom"))
          :repository {"test" {:url (repo-url)
                               :username "dantheman"
                               :password token}}
          :local-repo help/local-repo)))
    (is (thrown-with-msg?
         org.sonatype.aether.deployment.DeploymentException
         #"Forbidden - the name in the pom \(test\) does not match the name you are deploying to \(toast\)"
         (aether/deploy
          :coordinates '[fake/toast "0.0.1"]
          :jar-file (io/file (io/resource "test.jar"))
          :pom-file (io/file (io/resource "test-0.0.1/test.pom"))
          :repository {"test" {:url (repo-url)
                               :username "dantheman"
                               :password token}}
          :local-repo help/local-repo)))
    (is (thrown-with-msg?
         org.sonatype.aether.deployment.DeploymentException
         #"Forbidden - the version in the pom \(0.0.1\) does not match the version you are deploying to \(1.0.0\)"
         (aether/deploy
          :coordinates '[fake/test "1.0.0"]
          :jar-file (io/file (io/resource "test.jar"))
          :pom-file (io/file (io/resource "test-0.0.1/test.pom"))
          :repository {"test" {:url (repo-url)
                               :username "dantheman"
                               :password token}}
          :local-repo help/local-repo)))))

(deftest deploy-requires-lowercase-group
  (-> (session (help/app-from-system))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app-from-system)) "dantheman" "password" "testing")]
    (is (thrown-with-msg? org.sonatype.aether.deployment.DeploymentException
                          #"Forbidden - group names must consist solely of lowercase"
                          (aether/deploy
                           :coordinates '[faKE/test "0.0.1"]
                           :jar-file (io/file (io/resource "test.jar"))
                           :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.1/test.pom"))
                                                       {:groupId "faKE"})
                           :repository {"test" {:url (repo-url)
                                                :username "dantheman"
                                                :password token}}
                           :local-repo help/local-repo)))))

(deftest deploy-requires-lowercase-project
  (-> (session (help/app-from-system))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app-from-system)) "dantheman" "password" "testing")]
    (is (thrown-with-msg? org.sonatype.aether.deployment.DeploymentException
                          #"Forbidden - project names must consist solely of lowercase"
                          (aether/deploy
                           :coordinates '[fake/teST "0.0.1"]
                           :jar-file (io/file (io/resource "test.jar"))
                           :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.1/test.pom"))
                                                       {:artifactId "teST"})
                           :repository {"test" {:url (repo-url)
                                                :username "dantheman"
                                                :password token}}
                           :local-repo help/local-repo)))))

(deftest deploy-requires-ascii-version
  (-> (session (help/app-from-system))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app-from-system)) "dantheman" "password" "testing")]
    (is (thrown-with-msg? org.sonatype.aether.deployment.DeploymentException
                          #"Forbidden - version strings must consist solely of letters"
                          (aether/deploy
                           :coordinates '[fake/test "1.α.0"]
                           :jar-file (io/file (io/resource "test.jar"))
                           :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.1/test.pom"))
                                                       {:version "1.α.0"})
                           :repository {"test" {:url (repo-url)
                                                :username "dantheman"
                                                :password token}}
                           :local-repo help/local-repo)))))

(deftest put-on-html-fails
  (let [sess (-> (session (help/app-from-system))
                 (register-as "dantheman" "test@example.org" "password"))
        token (create-deploy-token (session (help/app-from-system)) "dantheman" "password" "testing")]
    (-> sess
        (visit "/repo/group/artifact/1.0.0/injection.html"
               :request-method :put
               :headers {"authorization"
                         (str "Basic "
                              (String. (base64/encode
                                        (.getBytes (str "dantheman:" token)
                                                   "UTF-8"))
                                       "UTF-8"))}
               :body "XSS here")
        (has (status? 400)))))

(deftest put-using-dotdot-fails
  (let [sess (-> (session (help/app-from-system))
                 (register-as "dantheman" "test@example.org" "password"))
        token (create-deploy-token (session (help/app-from-system)) "dantheman" "password" "testing")
        headers {"authorization"
                 (str "Basic "
                      (String. (base64/encode
                                (.getBytes (str "dantheman:" token)
                                           "UTF-8"))
                               "UTF-8"))}]
    (-> sess
        (visit "/repo/../artifact/1.0.0/test.jar" :request-method :put
               :headers headers
               :body "Bad jar")
        (has (status? 400))
        (visit "/repo/group/..%2F..%2Fasdf/1.0.0/test.jar" :request-method :put
               :headers headers
               :body "Bad jar")
        (has (status? 400))
        (visit "/repo/group/artifact/..%2F..%2F..%2F1.0.0/test.jar"
               :request-method :put
               :headers headers
               :body "Bad jar")
        (has (status? 400))
        (visit "/repo/group/artifact/1.0.0/..%2F..%2F..%2F..%2F/test.jar"
               :request-method :put
               :headers headers
               :body "Bad jar")
        (has (status? 400)))))

(deftest does-not-write-incomplete-file
  (-> (session (help/app-from-system))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app-from-system)) "dantheman" "password" "testing")]
    (with-out-str
      (-> (session (help/app-from-system))
          (visit "/repo/group3/artifact3/1.0.0/test.jar"
                 :body (proxy [java.io.InputStream] []
                         (read
                           ([] (throw (java.io.IOException.)))
                           ([^bytes bytes] (throw (java.io.IOException.)))
                           ([^bytes bytes off len] (throw (java.io.IOException.)))))
                 :request-method :put
                 :content-length 1000
                 :content-type "txt/plain"
                 :headers {:content-length 1000
                           :content-type "txt/plain"
                           :authorization (str "Basic "
                                               (String. (base64/encode
                                                         (.getBytes (str "dantheman:" token)
                                                                    "UTF-8"))
                                                        "UTF-8"))})
          (has (status? 403)))))
  (is (not (.exists (io/file (:repo (config)) "group3/artifact3/1.0.0/test.jar")))))
