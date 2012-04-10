(defproject clojars-web "0.7.0-SNAPSHOT"
  :dependencies [[org.clojure/clojure "1.3.0"]
                 [org.clojure/java.jdbc "0.1.3"]
                 [org.apache.maven/maven-model "3.0.4"]
                 [com.cemerick/pomegranate "0.0.10"]
                 [compojure "1.0.1"]
                 [ring/ring-jetty-adapter "1.0.2"]
                 [hiccup "0.3.8"]
                 [cheshire "2.2.2"]
                 [org.mindrot/jbcrypt "0.3m"]
                 [org.clojars.ato/nailgun "0.7.1"]
                 [org.xerial/sqlite-jdbc "3.6.17"]
                 [org.apache.commons/commons-email "1.2"]]
  :profiles {:test {:resource-paths ["test-resources"]
                    :dependencies [[kerodon "0.0.4"]
                                   [nailgun-shim "0.0.1"]]}}
  :plugins [[lein-ring "0.6.3"]]
  :ring {:handler clojars.web/clojars-app}
  :aot [clojars.scp]
  :main clojars.main
  :min-lein-version "2.0.0")


