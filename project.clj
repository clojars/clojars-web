(defproject clojars-web "0.11.3-SNAPSHOT"
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [org.clojure/tools.cli "0.2.1"]
                 [org.apache.maven/maven-model "3.0.4"
                  :exclusions
                  [org.codehaus.plexus/plexus-utils]]
                 [com.cemerick/pomegranate "0.0.13"
                  :exclusions
                  [org.apache.httpcomponents/httpcore]]
                 [s3-wagon-private "1.0.0"]
                 [compojure "1.1.1"]
                 [ring/ring-jetty-adapter "1.1.1"]
                 [hiccup "1.0.1"]
                 [cheshire "2.2.2"]
                 [korma "0.3.0-beta10"]
                 [org.clojars.ato/nailgun "0.7.1"]
                 [org.xerial/sqlite-jdbc "3.6.17"]
                 [org.apache.commons/commons-email "1.2"]
                 [net.cgrand/regex "1.0.1" :exclusions [org.clojure/clojure]]
                 [clj-time "0.3.8"]
                 [com.cemerick/friend "0.0.8"
                  :exclusions [org.openid4java/openid4java-nodeps]]
                 [clj-stacktrace "0.2.4"]
                 [ring-anti-forgery "0.2.0"]]
  :profiles {:test {:resource-paths ["test-resources"]
                    :dependencies [[kerodon "0.0.6"]
                                   [nailgun-shim "0.0.1"]]}
             :dev {:dependencies [[kerodon "0.0.6"]
                                  [nailgun-shim "0.0.1"]]
                   :resource-paths ["local-resources"]}}
  :plugins [[lein-ring "0.7.3" :exclusions [thneed]]
            ;fix downloading -snapshot all the time
            [thneed "1.0.0"]]
  :ring {:handler clojars.web/clojars-app}
  :aot [clojars.scp]
  :main clojars.main
  :min-lein-version "2.0.0")


