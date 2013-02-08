(defproject clojars-web "0.13.3"
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [org.clojure/tools.cli "0.2.1"]
                 [org.apache.maven/maven-model "3.0.4"
                  :exclusions
                  [org.codehaus.plexus/plexus-utils]]
                 [com.cemerick/pomegranate "0.0.13"
                  :exclusions
                  [org.apache.httpcomponents/httpcore]]
                 [s3-wagon-private "1.0.0"]
                 [compojure "1.1.3"
                  :exclusions [org.clojure/core.incubator]]
                 [ring/ring-jetty-adapter "1.1.1"]
                 [hiccup "1.0.1"]
                 [cheshire "3.0.0"]
                 [korma "0.3.0-beta10"]
                 [org.clojars.ato/nailgun "0.7.1"]
                 [org.xerial/sqlite-jdbc "3.7.2"]
                 [org.apache.commons/commons-email "1.2"]
                 [commons-codec "1.6"]
                 [net.cgrand/regex "1.0.1"
                  :exclusions [org.clojure/clojure]]
                 [clj-time "0.3.8"]
                 [com.cemerick/friend "0.1.2"
                  :exclusions [org.openid4java/openid4java-nodeps]]
                 [clj-stacktrace "0.2.4"]
                 [ring-anti-forgery "0.2.0"]
                 [valip "0.2.0"]
                 [clucy "0.3.0"]
                 [org.clojure/tools.nrepl "0.2.0-RC1"]
                 [org.bouncycastle/bcpg-jdk15on "1.47"]]
  :profiles {:test {:resource-paths ["test-resources"]
                    :dependencies [[kerodon "0.0.7"]
                                   [nailgun-shim "0.0.1"]]}
             :dev {:dependencies [[kerodon "0.0.7"]
                                  [nailgun-shim "0.0.1"]]
                   :resource-paths ["local-resources"]}}
  :plugins [[lein-ring "0.7.3" :exclusions [thneed]]
            ;fix downloading -snapshot all the time
            [thneed "1.0.0"]]
  :aliases {"migrate" ["run" "-m" "clojars.db.migrate"]}
  :ring {:handler clojars.web/clojars-app}
  :aot [clojars.scp]
  :main clojars.main
  :min-lein-version "2.0.0")
