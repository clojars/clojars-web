(defproject clojars-web "150-SNAPSHOT"
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/core.memoize "0.8.2"]
                 [raven-clj "1.4.3"
                  :exclusions [cheshire]]
                 [org.apache.maven/maven-model "3.0.4"
                  :exclusions
                  [org.codehaus.plexus/plexus-utils]]
                 [com.cemerick/pomegranate "0.3.1"
                  :exclusions
                  [commons-logging
                   org.apache.httpcomponents/httpcore
                   org.codehaus.plexus/plexus-utils]]
                 ;; pomegranate transitively depends on two versions, so we explicitly bring in one
                 [org.codehaus.plexus/plexus-utils "3.0"]
                 [ring-middleware-format "0.7.4"
                  :exclusions [ring/ring-core
                               cheshire
                               com.fasterxml.jackson.core/jackson-core
                               com.fasterxml.jackson.dataformat/jackson-dataformat-smile]]
                 [org.apache.commons/commons-email "1.5"]
                 [net.cgrand/regex "1.0.1"
                  :exclusions [org.clojure/clojure]]
                 [com.cemerick/friend "0.2.3"
                  :exclusions [com.google.inject/guice
                               commons-codec
                               commons-io
                               commons-logging
                               org.apache.httpcomponents/httpclient
                               org.apache.httpcomponents/httpcore
                               org.clojure/core.cache
                               ring/ring-core
                               slingshot]]
                 [com.github.scribejava/scribejava-apis "7.1.1"]
                 [buddy/buddy-core "1.6.0"
                  :exclusions [commons-codec]]
                 [clj-stacktrace "0.2.8"]
                 [clj-time "0.11.0"]
                 [ring/ring-anti-forgery "1.0.1"
                  :exclusions [commons-codec]]
                 [valip "0.2.0"
                  :exclusions [commons-logging]]
                 [clucy "0.3.0"]
                 [org.clojure/tools.nrepl "0.2.11"]
                 [yesql "0.5.1"]
                 [org.postgresql/postgresql "42.2.9"]
                 [duct/hikaricp-component "0.1.2"
                  :exclusions [com.stuartsierra/component
                               org.slf4j/slf4j-api]]
                 [duct "0.8.0"
                  :exclusions [org.clojure/tools.reader]]
                 [ring/ring-core "1.8.0"]
                 [ring/ring-jetty-adapter "1.8.0"]
                 [ring-jetty-component "0.3.1"
                  :exclusions [org.clojure/tools.reader
                               ring/ring-core]]
                 [digest "1.4.4"]
                 [clj-http "3.10.0"
                  :exclusions [commons-codec
                               commons-io]]
                 [aero "1.1.3"]
                 [one-time "0.5.0"
                  :exclusions [commons-codec]]

                 ;; logging
                 [org.clojure/tools.logging "1.1.0"]
                 [ch.qos.logback/logback-classic "1.3.0-alpha5"
                  :exclusions [com.sun.mail/javax.mail]]
                 
                 ;; AWS
                 [com.cognitect.aws/api "0.8.408"
                  :exclusions [org.eclipse.jetty/jetty-util]]
                 [com.cognitect.aws/endpoints "1.1.11.705"]
                 [com.cognitect.aws/s3 "784.2.593.0"]]
  :plugins [[supersport "1"]]
  :main ^:skip-aot clojars.main
  :target-path "target/%s/"
  :release-tasks [["vcs" "assert-committed"]
                  ["change" "version" "super.sport/bump-version" "release"]
                  ["vcs" "commit"]
                  ["vcs" "tag"]
                  ["change" "version" "super.sport/bump-version"]
                  ["vcs" "commit"]
                  ["vcs" "push"]]
  :aliases {"migrate" ["run" "-m" "clojars.tools.migrate-db"]}
  :pedantic? :warn
  :profiles
  {:dev  [:project/dev :profiles/dev]
   :repl {:pedantic? false}
   :test [:project/test :profiles/test]
   :uberjar {:aot :all}
   :profiles/dev  {}
   :profiles/test {}
   :project/dev   {:source-paths ["dev"]
                   :repl-options {:init-ns user}
                   :dependencies [[reloaded.repl "0.2.0"]
                                  [org.clojure/tools.namespace "0.2.11"]
                                  [eftest "0.5.9"]
                                  [kerodon "0.7.0"
                                   :exclusions [clj-time
                                                org.apache.httpcomponents/httpcore
                                                org.flatland/ordered
                                                ring/ring-codec]]
                                  [net.polyc0l0r/bote "0.1.0"
                                   :exclusions [commons-codec
                                                javax.mail/mail
                                                org.clojars.kjw/slf4j-simple]]
                                  [nubank/matcher-combinators "3.1.4"]]
                   :resource-paths ["local-resources"]}
   :project/test  {}})
