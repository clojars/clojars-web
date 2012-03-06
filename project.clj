(defproject clojars-web "0.7.0-SNAPSHOT"
  :aot [clojars.scp]
  :main clojars.main
  :dependencies [[org.clojure/clojure "1.3.0"]
                 [org.clojure/java.jdbc "0.1.3"]
                 [org.apache.maven/maven-ant-tasks "2.0.10"]
                 [org.apache.maven/maven-artifact-manager "2.2.1"]
                 [org.apache.maven/maven-model "2.2.1"]
                 [org.apache.maven/maven-project "2.2.1"]
                 [compojure "1.0.1"]
                 [ring/ring-jetty-adapter "1.0.2"]
                 [hiccup "0.3.8"]
                 [cheshire "2.2.2"]
                 [org.mindrot/jbcrypt "0.3m"]
                 [org.clojars.ato/nailgun "0.7.1"]
                 [org.xerial/sqlite-jdbc "3.6.17"]
                 [org.apache.commons/commons-email "1.2"]]
  :dev-dependencies [[lein-ring "0.6.2"]
                     [kerodon "0.0.2"]
                     [nailgun-shim "0.0.1"]]
  :ring {:handler clojars.web/clojars-app})

