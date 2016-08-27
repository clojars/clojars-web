(defproject clojars-web "49-SNAPSHOT"
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/tools.cli "0.2.1"]
                 [yeller-clojure-client "1.4.2"
                  :exclusions [com.fasterxml.jackson.core/jackson-core
                               commons-codec]]
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
                 [ring-middleware-format "0.7.0"
                  :exclusions [ring/ring-core]]
                 [factual/durable-queue "0.1.5"]
                 [org.xerial/sqlite-jdbc "3.8.11.2"]
                 [org.apache.jclouds/jclouds-all "1.9.2"]
                 [org.clojure/tools.logging "0.3.1"] ;; required by jclouds
                 [org.apache.commons/commons-email "1.2"]
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
                               ring/ring-core]]
                 [clj-stacktrace "0.2.8"]
                 [ring/ring-anti-forgery "1.0.1"
                  :exclusions [commons-codec]]
                 [valip "0.2.0"
                  :exclusions [commons-logging]]
                 [clucy "0.3.0"]
                 [org.clojure/tools.nrepl "0.2.11"]
                 [yesql "0.5.1"]
                 [duct/hikaricp-component "0.1.0"
                  :exclusions [com.stuartsierra/component
                               org.slf4j/slf4j-api]]
                 ;; hikaricp-component transitively depends on two versions, so we explicitly bring in one
                 [org.slf4j/slf4j-api "1.7.7"]
                 [duct "0.8.0"]
                 [ring-jetty-component "0.3.1"]
                 [digest "1.4.4"]]
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
  :pedantic? :abort
  :profiles
  {:dev  [:project/dev  :profiles/dev]
   :repl {:pedantic? false}
   :test [:project/test :profiles/test]
   :uberjar {:aot :all}
   :profiles/dev  {}
   :profiles/test {}
   :project/dev   {:source-paths ["dev"]
                   :repl-options {:init-ns user}
                   :dependencies [[reloaded.repl "0.2.0"]
                                  [org.clojure/tools.namespace "0.2.11"]
                                  [eftest "0.1.0"]
                                  [kerodon "0.7.0"
                                   :exclusions [org.apache.httpcomponents/httpcore
                                                ring/ring-codec]]
                                  [clj-http "2.2.0"
                                   :exclusions [commons-codec
                                                commons-io
                                                slingshot
                                                clj-tuple]]
                                  [com.google.jimfs/jimfs "1.0"]
                                  [net.polyc0l0r/bote "0.1.0"
                                   :exclusions [commons-codec
                                                javax.mail/mail
                                                org.clojars.kjw/slf4j-simple]]]
                   :resource-paths ["local-resources"]}
   :project/test  {}})
