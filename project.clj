(defproject clojars-web "0.6.0"
  :main clojars.core
  :dependencies [[org.clojure/clojure "1.1.0"]
                 [org.clojure/clojure-contrib "1.1.0"]

                 [org.apache.maven/maven-ant-tasks "2.0.10"]
                 [org.apache.maven/maven-artifact-manager "2.2.1"]
                 [org.apache.maven/maven-model "2.2.1"]
                 [org.apache.maven/maven-project "2.2.1"]
                 [org.apache.maven.wagon/wagon-file "1.0-beta-6"]

                 [compojure "0.3.2"]
                 [org.clojars.ato/nailgun "0.7.1"]
                 [org.xerial/sqlite-jdbc "3.6.17"]])

