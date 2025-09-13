(ns clojars.integration.uploads-test
  (:require
   [buddy.core.codecs :as codecs]
   [cemerick.pomegranate.aether :as aether]
   [clj-http.client :as client]
   [clj-http.cookies :as http-cookies]
   [clj-http.core :as http-core]
   [clojars.config :refer [config]]
   [clojars.db :as db]
   [clojars.email :as email]
   [clojars.file-utils :as fu]
   [clojars.http-utils :refer [clear-sessions!]]
   [clojars.integration.steps :refer [create-deploy-token login-as register-as]]
   [clojars.s3 :as s3]
   [clojars.test-helper :as help]
   [clojars.web.common :as common]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [are deftest is testing use-fixtures]]
   [kerodon.core :refer [choose fill-in follow follow-redirect press session uncheck visit within]]
   [kerodon.test :refer [has status? text?]]
   [net.cgrand.enlive-html :as enlive]
   [next.jdbc.sql :as sql])
  (:import
   (java.sql
    Timestamp)
   (org.eclipse.aether.deployment
    DeploymentException)))

(help/register-http-wagon!)

(use-fixtures :each
  help/default-fixture
  help/with-local-repos
  help/with-clean-database
  help/run-test-app)

(defn repo-url
  []
  (format "http://localhost:%s/repo"
          help/test-port))

(defn- auth-header
  [username token]
  (str "Basic "
       (codecs/bytes->b64-str
        (.getBytes (format "%s:%s" username token)
                   "UTF-8"))))

(defn deploy
  [{:keys [artifact-map coordinates jar-file password pom-file module-file transfer-listener username retain-session?]
    :or {transfer-listener (fn [])
         username "dantheman"}}]
  ;; HttpWagon uses a static, inaccessible http client that caches cookies, so
  ;; we have to clear sessions on each deploy to mimic having new processes
  ;; deploying.
  (when-not retain-session?
    (clear-sessions!))
  (aether/deploy
   :coordinates coordinates
   :artifact-map (if module-file
                   (merge artifact-map {[:extension "module"] module-file})
                   artifact-map)
   :jar-file jar-file
   :pom-file pom-file
   :repository {"test" {:url (repo-url)
                        :username username
                        :password password}}
   :local-repo help/local-repo
   :transfer-listener transfer-listener))

(defn tmp-file [f name]
  (let [tmp-f (doto (io/file help/tmp-dir name)
                (.deleteOnExit))]
    (io/copy f tmp-f)
    tmp-f))

(defn tmp-checksum-file [f type]
  (doto (fu/create-checksum-file f type)
    .deleteOnExit))

(deftest user-can-register-and-deploy
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app)) "dantheman" "password" "testing")
        now (db/get-time)]
    (with-redefs [db/get-time (constantly now)]
      (deploy
       {:coordinates '[org.clojars.dantheman/test "0.0.1"]
        :jar-file (io/file (io/resource "test.jar"))
        :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.1/test.pom"))
                                    {:groupId "org.clojars.dantheman"})
        :password token}))

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

    (help/match-audit {:username "dantheman"}
                      {:tag "deployed"
                       :user "dantheman"
                       :group_name "org.clojars.dantheman"
                       :jar_name "test"
                       :version "0.0.1"})

    (-> (session (help/app))
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
    (-> (session (help/app))
        (visit "/")
        (fill-in [:#search] "test")
        (press [:#search-button])
        (within [:div.result
                 :div
                 :div]
          (has (text? "org.clojars.dantheman/test 0.0.1"))))
    (-> (session (help/app))
        (login-as "dantheman" "password")
        (visit "/tokens")
        (within [:td.last-used]
          (has (text? (common/format-timestamp now)))))))

(deftest user-can-deploy-using-email-address
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app)) "dantheman" "password" "testing")]
    (deploy
     {:coordinates '[org.clojars.dantheman/test "0.0.1"]
      :jar-file (io/file (io/resource "test.jar"))
      :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.1/test.pom"))
                                  {:groupId "org.clojars.dantheman"})
      :username "test@example.org"
      :password token}))
  ;; This test throws on failure, so we have this assertion to satisfy kaocha
  (is true))

(deftest deploying-with-a-scoped-token
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password"))

  (let [unscoped-token (create-deploy-token
                        (session (help/app)) "dantheman" "password" "unscoped")
        base-path      "org/clojars/dantheman/test/"
        repo           (:repo (config))]

    (help/add-verified-group "dantheman" "org.dantheman")
    ;; deploy to the groups with an unscoped token so they will show
    ;; as options for token scoping
    (deploy
     {:coordinates '[org.clojars.dantheman/test "0.0.1"]
      :jar-file (io/file (io/resource "test.jar"))
      :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.1/test.pom"))
                                  {:groupId "org.clojars.dantheman"})
      :password  unscoped-token})

    (deploy
     {:coordinates '[org.dantheman/test "0.0.1"]
      :jar-file (io/file (io/resource "test.jar"))
      :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.1/test.pom"))
                                  {:groupId "org.dantheman"})
      :password  unscoped-token})

    (testing "with a group-scoped token"
      (let [group-scoped-token (create-deploy-token
                                (session (help/app)) "dantheman" "password" "group-scoped"
                                {:scope "org.clojars.dantheman"})]

        (deploy
         {:coordinates '[org.clojars.dantheman/test "0.0.2"]
          :jar-file (io/file (io/resource "test.jar"))
          :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.1/test.pom"))
                                      {:groupId "org.clojars.dantheman"
                                       :version "0.0.2"})
          :password  group-scoped-token})

        (is (= 6 (count (.list (io/file repo base-path "0.0.2")))))

        (is (thrown-with-msg?
             DeploymentException
             #"Forbidden - The provided token's scope doesn't allow deploying this artifact"
              (deploy
               {:coordinates '[org.dantheman/test "0.0.2"]
                :jar-file (io/file (io/resource "test.jar"))
                :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.1/test.pom"))
                                            {:groupId "org.dantheman"
                                             :version "0.0.2"})
                :password  group-scoped-token})))))

    (testing "with an artifact-scoped token"
      (let [artifact-scoped-token (create-deploy-token
                                   (session (help/app)) "dantheman" "password" "artifact-scoped"
                                   {:scope "org.clojars.dantheman/test"})]

        (deploy
         {:coordinates '[org.clojars.dantheman/test "0.0.3"]
          :jar-file (io/file (io/resource "test.jar"))
          :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.1/test.pom"))
                                      {:groupId "org.clojars.dantheman"
                                       :version "0.0.3"})
          :password  artifact-scoped-token})

        (is (= 6 (count (.list (io/file repo base-path "0.0.3")))))

        (is (thrown-with-msg?
             DeploymentException
             #"Forbidden - The provided token's scope doesn't allow deploying this artifact"
              (deploy
               {:coordinates '[org.clojars.dantheman/test2 "0.0.1"]
                :jar-file (io/file (io/resource "test.jar"))
                :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.1/test.pom"))
                                            {:groupId "org.clojars.dantheman"
                                             :artifactId "test2"})
                :password  artifact-scoped-token})))))))

(deftest deploying-with-a-single-use-token
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password"))

  (let [token (create-deploy-token
               (session (help/app))
               "dantheman" "password" "single-use" {:single-use? true})]


    (testing "first deploy with single-use-token works"
      (deploy
       {:coordinates '[org.clojars.dantheman/test "0.0.1"]
        :jar-file (io/file (io/resource "test.jar"))
        :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.1/test.pom"))
                                    {:groupId "org.clojars.dantheman"})
        :password token}))

    (testing "reuse of token fails"
      (is (thrown-with-msg?
           DeploymentException
           #"The provided single-use token has already been used"
            (deploy
             {:coordinates '[org.clojars.dantheman/test "0.0.2"]
              :jar-file (io/file (io/resource "test.jar"))
              :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.1/test.pom"))
                                          {:groupId "org.clojars.dantheman"
                                           :version "0.0.2"})
              :password  token}))))))

(deftest deploying-with-an-expiring-token
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password"))

  (let [token (create-deploy-token
               (session (help/app))
               "dantheman" "password" "single-use" {:expires-in "1 Hour"})]

    (testing "deploy before token expires works"
      (deploy
       {:coordinates '[org.clojars.dantheman/test "0.0.1"]
        :jar-file (io/file (io/resource "test.jar"))
        :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.1/test.pom"))
                                    {:groupId "org.clojars.dantheman"})
        :password token}))

    (testing "deploy after token expires fails"
      (help/with-time (Timestamp. (+ (.getTime (db/get-time)) (* 2 1000 60 60))) ;; 2 hours
        (is (thrown-with-msg?
             DeploymentException
             #"401 Unauthorized"
              (deploy
               {:coordinates '[org.clojars.dantheman/test "0.0.2"]
                :jar-file (io/file (io/resource "test.jar"))
                :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.1/test.pom"))
                                            {:groupId "org.clojars.dantheman"
                                             :version "0.0.2"})
                :password  token})))

        (help/match-audit {:username "dantheman"}
                          {:user "dantheman"
                           :tag "expired-token"
                           :message "The given token is expired"})))))

(deftest user-cannot-deploy-with-disabled-token
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app)) "dantheman" "password" "testing")
        db (:db (config))
        db-token (first (db/find-user-tokens-by-username db "dantheman"))]
    (db/disable-deploy-token db (:id db-token))
    (is (thrown-with-msg?
         DeploymentException
         #"401 Unauthorized"
          (deploy
           {:coordinates '[org.clojars.dantheman/test "0.0.1"]
            :jar-file (io/file (io/resource "test.jar"))
            :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.1/test.pom"))
                                        {:groupId "org.clojars.dantheman"})
            :password  token})))

    (help/match-audit {:username "dantheman"}
                      {:user "dantheman"
                       :tag "invalid-token"
                       :message "The given token either doesn't exist, isn't yours, or is disabled"})))

(deftest user-can-deploy-artifacts-after-maven-metadata
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app)) "dantheman" "password" "testing")
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
    (binding [http-core/*cookie-store* (http-cookies/cookie-store)]
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

(deftest user-can-deploy-artifacts-after-maven-metadata-using-single-use-token
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app)) "dantheman" "password" "testing" {:single-use? true})
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
    (binding [http-core/*cookie-store* (http-cookies/cookie-store)]
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

(deftest user-cannot-deploy-to-groups-without-permission
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password"))
  (-> (session (help/app))
      (register-as "fixture" "fixture@example.org" "password"))
  (let [token (create-deploy-token (session (help/app)) "dantheman" "password" "testing")]
    (is (thrown-with-msg? DeploymentException
                          #"Forbidden - You don't have access to the 'org\.clojars\.fixture/test' project"
          (deploy
           {:coordinates '[org.clojars.fixture/test "0.0.1"]
            :jar-file (io/file (io/resource "test.jar"))
            :pom-file (io/file (io/resource "test-0.0.1/test.pom"))
            :password  token})))
    (help/match-audit {:username "dantheman"}
                      {:user "dantheman"
                       :tag "deploy-forbidden"
                       :group_name "org.clojars.fixture"
                       :jar_name "test"
                       :version "0.0.1"
                       :message "You don't have access to the 'org.clojars.fixture/test' project"})))

(deftest user-can-deploy-to-group-when-not-admin
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password"))
  (-> (session (help/app))
      (register-as "fixture" "fixture@example.org" "password")
      (visit "/groups/org.clojars.fixture")
      (fill-in [:#username] "dantheman")
      (press "Add Permission"))
  (let [token (create-deploy-token (session (help/app)) "dantheman" "password" "testing")]
    (deploy
     {:coordinates '[org.clojars.fixture/test "0.0.1"]
      :jar-file (io/file (io/resource "test.jar"))
      :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.1/test.pom"))
                                  {:groupId "org.clojars.fixture"})
      :password  token}))
  ;; This test throws on failure, so we have this assertion to satisfy kaocha
  (is true))

(deftest user-can-deploy-to-group-when-permission-scoped-to-the-project
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password"))
  (-> (session (help/app))
      (register-as "fixture" "fixture@example.org" "password"))
  ;; Deploy once so the project exists to be scoped to below
  (let [token (create-deploy-token (session (help/app)) "fixture" "password" "testing")]
    (deploy
     {:coordinates '[org.clojars.fixture/test "0.0.1"]
      :jar-file (io/file (io/resource "test.jar"))
      :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.1/test.pom"))
                                  {:groupId "org.clojars.fixture"})
      :username "fixture"
      :password  token}))
  (-> (session (help/app))
      (login-as "fixture" "password")
      (follow-redirect)
      (visit "/groups/org.clojars.fixture")
      (fill-in [:#username] "dantheman")
      (choose [:#scope_to_jar_select] "test")
      (press "Add Permission"))
  (let [token (create-deploy-token (session (help/app)) "dantheman" "password" "testing")]
    (deploy
     {:coordinates '[org.clojars.fixture/test "0.0.2"]
      :jar-file (io/file (io/resource "test.jar"))
      :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.1/test.pom"))
                                  {:groupId "org.clojars.fixture"
                                   :version "0.0.2"})
      :password  token})
    (testing "User can't deploy to another project in the group if it only has rights to a single project"
      (is (thrown-with-msg?
           DeploymentException
           #"Forbidden - You don't have access to the 'org.clojars.fixture/test2' project"
            (deploy
             {:coordinates '[org.clojars.fixture/test2 "0.0.1"]
              :jar-file (io/file (io/resource "test.jar"))
              :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.1/test.pom"))
                                          {:groupId "org.clojars.fixture"
                                           :artifactId "test2"})
              :password  token}))))))

(deftest user-cannot-deploy-to-a-non-existent-group
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app)) "dantheman" "password" "testing")]
    (is (thrown-with-msg? DeploymentException
                          #"Forbidden - Group 'new-group' doesn't exist. See https://bit.ly/3MuKGXO"
          (deploy
           {:coordinates '[new-group/test "0.0.1"]
            :jar-file (io/file (io/resource "test.jar"))
            :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.1/test.pom"))
                                        {:groupId "dashboard"})
            :password  token})))

    (help/match-audit {:username "dantheman"}
                      {:user "dantheman"
                       :group_name "new-group"
                       :jar_name "test"
                       :version "0.0.1"
                       :message "Group 'new-group' doesn't exist. See https://bit.ly/3MuKGXO"
                       :tag "deploy-forbidden"})))

(deftest user-can-deploy-a-new-version-to-an-existing-project-in-a-non-verified-group
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app)) "dantheman" "password" "testing")]

    ;; create a prior version in a non-verified group - we have to do
    ;; this directly and prevent a verified group check since we can
    ;; longer deploy to create the group or project
    (db/add-admin help/*db* "legacy-group" db/SCOPE-ALL "dantheman" "testing")
    (with-redefs [db/check-group+project (constantly nil)]
      (db/add-jar help/*db* "dantheman" {:group "legacy-group"
                                         :name "test"
                                         :version "0.0.1-SNAPSHOT"}))

    (deploy
     {:coordinates '[legacy-group/test "0.0.1"]
      :jar-file (io/file (io/resource "test.jar"))
      :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.1/test.pom"))
                                  {:groupId "legacy-group"})
      :password  token})

    (help/match-audit {:username "dantheman"}
                      {:tag "deployed"
                       :user "dantheman"
                       :group_name "legacy-group"
                       :jar_name "test"
                       :version "0.0.1"})

    (-> (session (help/app))
        (visit "/")
        (fill-in [:#search] "test")
        (press [:#search-button])
        (within [:div.result
                 :div
                 :div]
          (has (text? "legacy-group/test 0.0.1"))))))

(deftest user-cannot-deploy-new-project-to-non-verified-group
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app)) "dantheman" "password" "testing")]
    (db/add-admin help/*db* "legacy-group" db/SCOPE-ALL "dantheman" "testing")

    (is (thrown-with-msg? DeploymentException
                          #"Forbidden - Group 'legacy-group' isn't verified, so can't contain new projects. See https://bit.ly/3MuKGXO"
          (deploy
           {:coordinates '[legacy-group/test "0.0.1"]
            :jar-file (io/file (io/resource "test.jar"))
            :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.1/test.pom"))
                                        {:groupId "dashboard"})
            :password  token})))

    (help/match-audit {:username "dantheman"}
                      {:user "dantheman"
                       :group_name "legacy-group"
                       :jar_name "test"
                       :version "0.0.1"
                       :message "Group 'legacy-group' isn't verified, so can't contain new projects. See https://bit.ly/3MuKGXO"
                       :tag "deploy-forbidden"})))

(deftest user-cannot-redeploy
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app)) "dantheman" "password" "testing")]
    (deploy
     {:coordinates '[org.clojars.dantheman/test "0.0.1"]
      :jar-file (io/file (io/resource "test.jar"))
      :pom-file (io/file (io/resource "test-0.0.1/test.pom"))
      :password  token})
    (is (thrown-with-msg?
         DeploymentException
         #"Forbidden - redeploying non-snapshots"
          (deploy
           {:coordinates '[org.clojars.dantheman/test "0.0.1"]
            :jar-file (io/file (io/resource "test.jar"))
            :pom-file (io/file (io/resource "test-0.0.1/test.pom"))
            :password  token})))
    (help/match-audit {:username "dantheman"}
                      {:user "dantheman"
                       :group_name "org.clojars.dantheman"
                       :jar_name "test"
                       :version "0.0.1"
                       :message "redeploying non-snapshots is not allowed. See https://bit.ly/3EYzhwT"
                       :tag "non-snapshot-redeploy"})))

(deftest deploy-cannot-shadow-central
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app)) "dantheman" "password" "testing")]
    (help/add-verified-group "dantheman" "org.tcrawley")
    (is (thrown-with-msg?
         DeploymentException
         #"Forbidden - shadowing Maven Central"
          (deploy
           {:coordinates '[org.tcrawley/dynapath "0.0.1"]
            :jar-file (io/file (io/resource "test.jar"))
            :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.1/test.pom"))
                                        {:groupId "org.tcrawley"
                                         :artifactId "dynapath"})
            :password  token})))

    (help/match-audit {:username "dantheman"}
                      {:user "dantheman"
                       :group_name "org.tcrawley"
                       :jar_name "dynapath"
                       :version "0.0.1"
                       :message "shadowing Maven Central artifacts is not allowed. See https://bit.ly/3rTLqxZ"
                       :tag "central-shadow"})))

(deftest deploy-cannot-shadow-central-unless-allowlisted
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app)) "dantheman" "password" "testing")]
    (help/add-verified-group "dantheman" "net.mikera")
    (deploy
     {:coordinates '[net.mikera/clojure-pom "0.0.1"]
      :jar-file (io/file (io/resource "test.jar"))
      :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.1/test.pom"))
                                  {:groupId "net.mikera"
                                   :artifactId "clojure-pom"})
      :password  token}))
  ;; This test throws on failure, so we have this assertion to satisfy kaocha
  (is true))

(deftest release-must-include-license
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app)) "dantheman" "password" "testing")]
    (is (thrown-with-msg?
         DeploymentException
         #"Forbidden - the POM file does not include a license"
          (deploy
           {:coordinates '[org.clojars.dantheman/test "0.0.1"]
            :jar-file (io/file (io/resource "test.jar"))
            :pom-file (io/file (io/resource "test-0.0.1/test-no-license.pom"))
            :password  token})))

    (help/match-audit {:username "dantheman"}
                      {:user "dantheman"
                       :group_name "org.clojars.dantheman"
                       :jar_name "test"
                       :version "0.0.1"
                       :message "the POM file does not include a license. See https://bit.ly/3PQunZU"
                       :tag "missing-license"})))

(deftest user-can-deploy-new-version-in-same-session
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app)) "dantheman" "password" "testing")]
    (deploy
     {:coordinates '[org.clojars.dantheman/test "0.0.1"]
      :jar-file (io/file (io/resource "test.jar"))
      :pom-file (io/file (io/resource "test-0.0.1/test.pom"))
      :password  token})
    (deploy
     {:coordinates '[org.clojars.dantheman/test "0.0.3-SNAPSHOT"]
      :jar-file (io/file (io/resource "test.jar"))
      :pom-file (io/file (io/resource "test-0.0.3-SNAPSHOT/test.pom"))
      :password  token
      :retain-session? true}))
  ;; This test throws on failure, so we have this assertion to satisfy kaocha
  (is true))

(deftest user-can-redeploy-snapshots
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app)) "dantheman" "password" "testing")]
    (deploy
     {:coordinates '[org.clojars.dantheman/test "0.0.3-SNAPSHOT"]
      :jar-file (io/file (io/resource "test.jar"))
      :pom-file (io/file (io/resource "test-0.0.3-SNAPSHOT/test.pom"))
      :password  token})
    (deploy
     {:coordinates '[org.clojars.dantheman/test "0.0.3-SNAPSHOT"]
      :jar-file (io/file (io/resource "test.jar"))
      :pom-file (io/file (io/resource "test-0.0.3-SNAPSHOT/test.pom"))
      :password  token}))
  ;; This test throws on failure, so we have this assertion to satisfy kaocha
  (is true))

(deftest user-can-deploy-snapshot-with-dot
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app)) "dantheman" "password" "testing")]
    (deploy
     {:coordinates '[org.clojars.dantheman/test.thing "0.0.3-SNAPSHOT"]
      :jar-file (io/file (io/resource "test.jar"))
      :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.3-SNAPSHOT/test.pom"))
                                  {:groupId "org.clojars.dantheman"
                                   :artifactId "test.thing"})
      :password  token}))
  ;; This test throws on failure, so we have this assertion to satisfy kaocha
  (is true))

(deftest snapshot-deploys-preserve-timestamp-version
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app)) "dantheman" "password" "testing")
        timestamped-jar (atom nil)]
    (deploy
     {:coordinates '[org.clojars.dantheman/test "0.0.3-SNAPSHOT"]
      :jar-file (io/file (io/resource "test.jar"))
      :pom-file (io/file (io/resource "test-0.0.3-SNAPSHOT/test.pom"))
      :password  token
      :transfer-listener #(when-let [name (get-in % [:resource :name])]
                            (when (.endsWith name ".jar")
                              (reset! timestamped-jar name)))})
    (is @timestamped-jar)
    (is (.exists (io/file (:repo (config)) @timestamped-jar)))))

(deftest deploys-sharing-the-same-session-work
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app)) "dantheman" "password" "testing")
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
    (binding [http-core/*cookie-store* (http-cookies/cookie-store)]
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
                     :basic-auth ["dantheman" token]}))))
  ;; This test throws on failure, so we have this assertion to satisfy kaocha
  (is true))

(deftest user-can-deploy-with-classifiers
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app)) "dantheman" "password" "testing")]
    (deploy
     {:coordinates '[org.clojars.dantheman/test "0.0.1"]
      :artifact-map {[:extension "jar"] (io/file (io/resource "test.jar"))
                     [:classifier "test" :extension "jar"] (io/file (io/resource "test.jar"))
                     [:extension "pom"] (io/file (io/resource "test-0.0.1/test.pom"))}
      :password token}))
  (are [ext] (.exists (io/file (:repo (config)) "org/clojars/dantheman" "test" "0.0.1"
                               (format "test-0.0.1%s" ext)))
    ".pom" ".jar" "-test.jar"))

(deftest user-can-deploy-snapshot-with-classifiers
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app)) "dantheman" "password" "testing")]
    (deploy
     {:coordinates '[org.clojars.dantheman/test "0.0.3-SNAPSHOT"]
      :artifact-map {[:extension "jar"] (io/file (io/resource "test.jar"))
                     [:classifier "test" :extension "jar"] (io/file (io/resource "test.jar"))
                     [:extension "pom"] (io/file (io/resource "test-0.0.3-SNAPSHOT/test.pom"))}
      :password token}))
  ;; we can't look for files directly since the version will be timestamped
  (is (= 3 (->> (file-seq (io/file (:repo (config)) "org/clojars/dantheman" "test" "0.0.3-SNAPSHOT"))
                (filter (memfn isFile))
                (filter #(re-find #"(pom|jar|-test\.jar)$" (.getName %)))
                count))))

(deftest user-can-deploy-with-gpg-signatures
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app)) "dantheman" "password" "testing")
        pom (io/file (io/resource "test-0.0.1/test.pom"))]
    (deploy
     {:coordinates '[org.clojars.dantheman/test "0.0.1"]
      :artifact-map {[:extension "jar"] (io/file (io/resource "test.jar"))
                     [:extension "pom"] pom
                     ;; any content will do since we don't validate signatures
                     [:extension "jar.asc"] pom
                     [:extension "pom.asc"] pom}
      :password token}))
  ;; This test throws on failure, so we have this assertion to satisfy kaocha
  (is true))

(deftest user-can-deploy-with-ssh-signatures
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app)) "dantheman" "password" "testing")
        pom (io/file (io/resource "test-0.0.1/test.pom"))]
    (deploy
     {:coordinates '[org.clojars.dantheman/test "0.0.1"]
      :artifact-map {[:extension "jar"] (io/file (io/resource "test.jar"))
                     [:extension "pom"] pom
                     ;; any content will do since we don't validate signatures
                     [:extension "jar.sig"] pom
                     [:extension "pom.sig"] pom}
      :password token}))
  ;; This test throws on failure, so we have this assertion to satisfy kaocha
  (is true))

(deftest missing-signature-fails-the-deploy
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app)) "dantheman" "password" "testing")
        pom (io/file (io/resource "test-0.0.1/test.pom"))]
    (doseq [suffix ["asc" "sig"]]
      (testing (format "with a suffix of .%s" suffix)
        (is (thrown-with-msg?
             DeploymentException
             #"test-0.0.1.pom has no signature"
              (deploy
               {:coordinates '[org.clojars.dantheman/test "0.0.1"]
                :artifact-map {[:extension "jar"] (io/file (io/resource "test.jar"))
                               [:extension "pom"] pom
                               ;; any content will do since we don't validate signatures
                               [:extension (format "jar.%s" suffix)] pom}
                :password token})))))

    (help/match-audit {:username "dantheman"}
                      {:user "dantheman"
                       :group_name "org.clojars.dantheman"
                       :jar_name "test"
                       :version "0.0.1"
                       :message "test-0.0.1.pom has no signature"
                       :tag "file-missing-signature"})))

(deftest anonymous-cannot-deploy
  (is (thrown-with-msg?
       DeploymentException
       #"Unauthorized"
        (deploy
         {:coordinates '[org.clojars.dantheman/test "1.0.0"]
          :jar-file (io/file (io/resource "test.jar"))
          :pom-file (io/file (io/resource "test-0.0.1/test.pom"))
          :repository {"test" {:url (repo-url)}}
          :local-repo help/local-repo}))))

(deftest bad-login-cannot-deploy
  (is (thrown-with-msg?
       DeploymentException
       #"Unauthorized - a deploy token is required to deploy"
        (deploy
         {:coordinates '[org.clojars.dantheman/test "1.0.0"]
          :jar-file (io/file (io/resource "test.jar"))
          :pom-file (io/file (io/resource "test-0.0.1/test.pom"))
          :password  "password"})))

  (help/match-audit {:username "dantheman"}
                    {:user    "dantheman"
                     :message "a deploy token is required to deploy. See https://bit.ly/3LmCclv"
                     :tag     "deploy-password-rejection"}))

(deftest deploy-requires-path-to-match-pom
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app)) "dantheman" "password" "testing")]
    (is (thrown-with-msg?
         DeploymentException
         #"Forbidden - the group in the pom \(org.clojars.dantheman\) does not match the group you are deploying to \(net.clojars.dantheman\)"
          (deploy
           {:coordinates '[net.clojars.dantheman/test "0.0.1"]
            :jar-file (io/file (io/resource "test.jar"))
            :pom-file (io/file (io/resource "test-0.0.1/test.pom"))
            :password  token})))

    (help/match-audit {:username "dantheman"}
                      {:user "dantheman"
                       :group_name "net.clojars.dantheman"
                       :jar_name "test"
                       :version "0.0.1"
                       :message "the group in the pom (org.clojars.dantheman) does not match the group you are deploying to (net.clojars.dantheman)"
                       :tag "pom-entry-mismatch"})

    (is (thrown-with-msg?
         DeploymentException
         #"Forbidden - the name in the pom \(test\) does not match the name you are deploying to \(toast\)"
          (deploy
           {:coordinates '[org.clojars.dantheman/toast "0.0.1"]
            :jar-file (io/file (io/resource "test.jar"))
            :pom-file (io/file (io/resource "test-0.0.1/test.pom"))
            :password  token})))

    (help/match-audit {:username "dantheman"}
                      {:user "dantheman"
                       :group_name "org.clojars.dantheman"
                       :jar_name "toast"
                       :version "0.0.1"
                       :message "the name in the pom (test) does not match the name you are deploying to (toast)"
                       :tag "pom-entry-mismatch"})

    (is (thrown-with-msg?
         DeploymentException
         #"Forbidden - the version in the pom \(0.0.1\) does not match the version you are deploying to \(1.0.0\)"
          (deploy
           {:coordinates '[org.clojars.dantheman/test "1.0.0"]
            :jar-file (io/file (io/resource "test.jar"))
            :pom-file (io/file (io/resource "test-0.0.1/test.pom"))
            :password  token})))

    (help/match-audit {:username "dantheman"}
                      {:user "dantheman"
                       :group_name "org.clojars.dantheman"
                       :jar_name "test"
                       :version "1.0.0"
                       :message "the version in the pom (0.0.1) does not match the version you are deploying to (1.0.0)"
                       :tag "pom-entry-mismatch"})))

(deftest deploy-requires-path-to-match-module
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app)) "dantheman" "password" "testing")]
    (is (thrown-with-msg?
         DeploymentException
         #"Forbidden - the component group in the gradle module \(org.clojars.dantheman\) does not match the coordinate you are deploying to \(net.clojars.dantheman\)"
          (deploy
           {:coordinates '[net.clojars.dantheman/test "0.0.1"]
            :jar-file (io/file (io/resource "test.jar"))
            :pom-file (io/file (io/resource "test-0.0.1/test.pom"))
            :module-file (io/file (io/resource "test-0.0.1/test.module"))
            :password  token})))

    (help/match-audit {:username "dantheman"}
                      {:user "dantheman"
                       :group_name "net.clojars.dantheman"
                       :jar_name "test"
                       :version "0.0.1"
                       :message "the component group in the gradle module (org.clojars.dantheman) does not match the coordinate you are deploying to (net.clojars.dantheman)"
                       :tag "module-entry-mismatch"})

    (is (thrown-with-msg?
         DeploymentException
         #"Forbidden - the component module in the gradle module \(test\) does not match the coordinate you are deploying to \(toast\)"
          (deploy
           {:coordinates '[org.clojars.dantheman/toast "0.0.1"]
            :jar-file (io/file (io/resource "test.jar"))
            :pom-file (io/file (io/resource "test-0.0.1/test.pom"))
            :module-file (io/file (io/resource "test-0.0.1/test.module"))
            :password  token})))

    (help/match-audit {:username "dantheman"}
                      {:user "dantheman"
                       :group_name "org.clojars.dantheman"
                       :jar_name "toast"
                       :version "0.0.1"
                       :message "the component module in the gradle module (test) does not match the coordinate you are deploying to (toast)"
                       :tag "module-entry-mismatch"})

    (is (thrown-with-msg?
         DeploymentException
         #"Forbidden - the component version in the gradle module \(0.0.1\) does not match the coordinate you are deploying to \(1.0.0\)"
          (deploy
           {:coordinates '[org.clojars.dantheman/test "1.0.0"]
            :jar-file (io/file (io/resource "test.jar"))
            :pom-file (io/file (io/resource "test-0.0.1/test.pom"))
            :module-file (io/file (io/resource "test-0.0.1/test.module"))
            :password  token})))

    (help/match-audit {:username "dantheman"}
                      {:user "dantheman"
                       :group_name "org.clojars.dantheman"
                       :jar_name "test"
                       :version "1.0.0"
                       :message "the component version in the gradle module (0.0.1) does not match the coordinate you are deploying to (1.0.0)"
                       :tag "module-entry-mismatch"})))

(deftest deploy-requires-lowercase-project
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app)) "dantheman" "password" "testing")]
    (is (thrown-with-msg? DeploymentException
                          #"Forbidden - project names must consist solely of lowercase"
          (deploy
           {:coordinates '[org.clojars.dantheman/teST "0.0.1"]
            :jar-file (io/file (io/resource "test.jar"))
            :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.1/test.pom"))
                                        {:artifactId "teST"})
            :password  token})))

    (help/match-audit {:username "dantheman"}
                      {:user "dantheman"
                       :group_name "org.clojars.dantheman"
                       :jar_name "teST"
                       :version "0.0.1"
                       :message "project names must consist solely of lowercase letters, numbers, hyphens and underscores. See https://bit.ly/3MuL20A"
                       :tag "regex-validation-failed"})))

(deftest deploy-requires-ascii-version
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app)) "dantheman" "password" "testing")]
    (is (thrown-with-msg? DeploymentException
                          #"Forbidden - version strings must consist solely of letters"
          (deploy
           {:coordinates '[org.clojars.dantheman/test "1.α.0"]
            :jar-file (io/file (io/resource "test.jar"))
            :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.1/test.pom"))
                                        {:version "1.α.0"})
            :password  token})))

    (help/match-audit {:username "dantheman"}
                      {:user "dantheman"
                       :group_name "org.clojars.dantheman"
                       :jar_name "test"
                       :version "1.α.0"
                       :message "version strings must consist solely of letters, numbers, dots, pluses, hyphens and underscores. See https://bit.ly/3Kf5KzX"
                       :tag "regex-validation-failed"})))

(deftest put-on-html-fails
  (let [sess (-> (session (help/app))
                 (register-as "dantheman" "test@example.org" "password"))
        token (create-deploy-token (session (help/app)) "dantheman" "password" "testing")]
    (-> sess
        (visit "/repo/group/artifact/1.0.0/injection.html"
               :request-method :put
               :headers {"authorization" (auth-header "dantheman" token)}
               :body "XSS here")
        (has (status? 400)))))

(deftest put-using-dotdot-fails
  (let [sess (-> (session (help/app))
                 (register-as "dantheman" "test@example.org" "password"))
        token (create-deploy-token (session (help/app)) "dantheman" "password" "testing")
        headers {"authorization" (auth-header "dantheman" token)}]
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
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app)) "dantheman" "password" "testing")]
    (with-out-str
      (-> (session (help/app))
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
                           :authorization (auth-header "dantheman" token)})
          (has (status? 403)))))
  (is (not (.exists (io/file (:repo (config)) "group3/artifact3/1.0.0/test.jar")))))

(deftest deploy-with-unhashed-token-writes-a-hash
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app)) "dantheman" "password" "testing")
        db (:db (config))
        {token-id :id} (db/find-token-by-value db token)
        _ (sql/update! db :deploy_tokens {:token_hash nil} {:id token-id})]
    (deploy
     {:coordinates '[org.clojars.dantheman/test "0.0.1"]
      :jar-file (io/file (io/resource "test.jar"))
      :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.1/test.pom"))
                                  {:groupId "org.clojars.dantheman"})
      :password  token})

    (let [{:keys [token_hash]} (db/find-token-by-value db token)]
      (is (= (db/hash-deploy-token token)
             token_hash)))))

(deftest deploy-sends-notification-emails
  (-> (session (help/app))
      (register-as "donthemon" "test2@example.org" "password"))
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password")
      (visit "/groups/org.clojars.dantheman")
      (fill-in [:#username] "donthemon")
      (press "Add Permission"))
  (let [token (create-deploy-token (session (help/app)) "dantheman" "password" "testing")]
    (email/expect-mock-emails 2)
    (deploy
     {:coordinates '[org.clojars.dantheman/test "0.0.1"]
      :jar-file (io/file (io/resource "test.jar"))
      :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.1/test.pom"))
                                  {:groupId "org.clojars.dantheman"})
      :password token})

    (is (true? (email/wait-for-mock-emails)))
    (let [emails @email/mock-emails
          addresses (into #{} (map first) emails)
          titles (map second emails)
          bodies (map #(nth % 2) emails)]
      (is (= #{"test@example.org" "test2@example.org"} addresses))
      (is (every? #(= "[Clojars] dantheman deployed org.clojars.dantheman/test 0.0.1" %)
                  titles))
      (is (every? #(re-find #"User 'dantheman' just deployed org.clojars.dantheman/test 0.0.1" %)
                  bodies))
      (is (every? #(re-find #"https://clojars.org/org.clojars.dantheman/test/versions/0.0.1" %)
                  bodies)))))

(deftest deploy-generates-repo-indexes
  (-> (session (help/app))
      (register-as "donthemon" "test2@example.org" "password"))
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password")
      (visit "/groups/org.clojars.dantheman")
      (fill-in [:#username] "donthemon")
      (press "Add Permission"))
  (let [token (create-deploy-token (session (help/app)) "dantheman" "password" "testing")]
    (deploy
     {:coordinates '[org.clojars.dantheman/test "0.0.1"]
      :jar-file (io/file (io/resource "test.jar"))
      :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.1/test.pom"))
                                  {:groupId "org.clojars.dantheman"})
      :password token})
    (let [{:keys [repo-bucket]} help/system
          paths ["" "org/" "org/clojars/" "org/clojars/dantheman/"
                 "org/clojars/dantheman/test/" "org/clojars/dantheman/test/0.0.1/"]]
      (is (help/wait-for-s3-key repo-bucket (format "%sindex.html" (peek paths))))
      (doseq [path paths]
        (testing (format "with a path of '%s'" path)
          (is (s3/object-exists? repo-bucket (format "%sindex.html" path))))))))

(deftest deploy-sends-notification-emails-only-when-enabled
  (-> (session (help/app))
      (register-as "donthemon" "test2@example.org" "password")
      (visit "/notification-preferences")
      (uncheck "Receive deploy notification emails?")
      (press "Update"))
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password")
      (visit "/groups/org.clojars.dantheman")
      (fill-in [:#username] "donthemon")
      (press "Add Permission"))
  (let [token (create-deploy-token (session (help/app)) "dantheman" "password" "testing")]
    (email/expect-mock-emails 1)
    (deploy
     {:coordinates '[org.clojars.dantheman/test "0.0.1"]
      :jar-file (io/file (io/resource "test.jar"))
      :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.1/test.pom"))
                                  {:groupId "org.clojars.dantheman"})
      :password token})

    (is (true? (email/wait-for-mock-emails)))
    (let [emails @email/mock-emails
          [[address title body]] emails]
      (is (= 1 (count emails)))
      (is (= "test@example.org" address))
      (is (= "[Clojars] dantheman deployed org.clojars.dantheman/test 0.0.1" title))
      (is (re-find #"User 'dantheman' just deployed org.clojars.dantheman/test 0.0.1" body))
      (is (re-find #"https://clojars.org/org.clojars.dantheman/test/versions/0.0.1" body)))))

(deftest user-can-deploy-a-pom-only-release
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password"))
  (let [token (create-deploy-token (session (help/app)) "dantheman" "password" "testing")]
    (deploy
     {:coordinates '[org.clojars.dantheman/test "0.0.1"]
      :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.1/test.pom"))
                                  {:groupId "org.clojars.dantheman"})
      :password  token})

    (help/match-audit {:username "dantheman"}
                      {:tag "deployed"
                       :user "dantheman"
                       :group_name "org.clojars.dantheman"
                       :jar_name "test"
                       :version "0.0.1"})

    (-> (session (help/app))
        (visit "/")
        (fill-in [:#search] "test")
        (press [:#search-button])
        (within [:div.result
                 :div
                 :div]
          (has (text? "org.clojars.dantheman/test 0.0.1"))))))

(deftest deploying-when-mfa-required
  (-> (session (help/app))
      (register-as "dantheman" "test@example.org" "password"))

  (let [token (create-deploy-token
               (session (help/app))
               "dantheman" "password" "testing")]

    (db/set-group-mfa-required help/*db* "org.clojars.dantheman" true)

    (testing "deploying without mfa fails"
      (is (thrown-with-msg?
           DeploymentException
           #"403 Forbidden - .* requires you to have two-factor auth enabled to deploy"
            (deploy
             {:coordinates '[org.clojars.dantheman/test "0.0.2"]
              :jar-file (io/file (io/resource "test.jar"))
              :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.1/test.pom"))
                                          {:groupId "org.clojars.dantheman"
                                           :version "0.0.2"})
              :password  token})))
      (help/match-audit {:username "dantheman"}
                        {:user "dantheman"
                         :group_name "org.clojars.dantheman"
                         :jar_name "test"
                         :version "0.0.2"
                         :message "The group 'org.clojars.dantheman' requires you to have two-factor auth enabled to deploy. See https://bit.ly/45qrtA8"
                         :tag "deploy-forbidden"}))

    (testing "deploy with mfa enabled works"
      (db/enable-otp! help/*db* "dantheman")
      (deploy
       {:coordinates '[org.clojars.dantheman/test "0.0.1"]
        :jar-file (io/file (io/resource "test.jar"))
        :pom-file (help/rewrite-pom (io/file (io/resource "test-0.0.1/test.pom"))
                                    {:groupId "org.clojars.dantheman"})
        :password token}))))
