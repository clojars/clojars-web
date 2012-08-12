(defproject clojars-web "0.9.2-SNAPSHOT"
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [org.clojure/tools.cli "0.2.1"]
                 [org.apache.maven/maven-model "3.0.4"
                  :exclusions
                  [org.codehaus.plexus/plexus-utils]]
                 [com.cemerick/pomegranate "0.0.13"]
                 [s3-wagon-private "1.0.0"]
                 [compojure "1.0.1"]
                 [ring/ring-jetty-adapter "1.0.2"]
                 [hiccup "0.3.8"]
                 [cheshire "2.2.2"]
                 [korma "0.3.0-beta10"]
                 [org.clojars.ato/nailgun "0.7.1"]
                 [org.xerial/sqlite-jdbc "3.6.17"]
                 [org.apache.commons/commons-email "1.2"]
                 [net.cgrand/regex "1.0.1"]
                 [clj-time "0.3.8"]
                 [com.cemerick/friend "0.0.8"]
                 [clj-stacktrace "0.2.4"]]
  :profiles {:test {:resource-paths ["test-resources"]
                    :dependencies [[kerodon "0.0.4"]
                                   [nailgun-shim "0.0.1"]]}
             :dev {:dependencies [[kerodon "0.0.4"]
                                  [nailgun-shim "0.0.1"]]
                   :resource-paths ["local-resources"]}}
  :plugins [[lein-ring "0.6.3"]]
  :ring {:handler clojars.web/clojars-app}
  :aot [clojars.scp]
  :main clojars.main
  :min-lein-version "2.0.0")


