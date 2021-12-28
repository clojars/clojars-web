(defproject clojars-web "155-SNAPSHOT"
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.10.3"]
                 [org.clojure/core.memoize "1.0.253"]
                 [raven-clj "1.6.0"
                  :exclusions [cheshire]]
                 [org.apache.maven/maven-model "3.8.4"]
                 [org.apache.maven/maven-repository-metadata "3.8.4"]
                 [org.codehaus.plexus/plexus-utils "3.4.1"]
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
                               slingshot
                               ;; not used, excluded to address CVE-2007-1652, CVE-2007-1651
                               org.openid4java/openid4java-nodeps
                               ;; not used, excluded to address CVE-2012-0881, CVE-2013-4002, CVE-2009-2625
                               net.sourceforge.nekohtml/nekohtml]]
                 [com.github.scribejava/scribejava-apis "8.3.1"]
                 [buddy/buddy-core "1.10.1"
                  :exclusions [commons-codec]]
                 [clj-stacktrace "0.2.8"]
                 [clj-time "0.15.2"]
                 [ring/ring-anti-forgery "1.3.0"
                  :exclusions [commons-codec]]
                 [valip "0.2.0"
                  :exclusions [commons-logging
                               commons-validator/commons-validator]]
                 ;; addresses CVE-2019-10086, CVE-2014-0114, CVE-2017-15708, CVE-2015-6420
                 [commons-validator/commons-validator "1.7"]
                 [clucy "0.3.0"]
                 [org.clojure/tools.nrepl "0.2.11"]
                 [yesql "0.5.3"]
                 [org.postgresql/postgresql "42.3.1"]
                 [duct/hikaricp-component "0.1.2"
                  :exclusions [com.stuartsierra/component
                               org.slf4j/slf4j-api]]
                 [duct "0.8.2"
                  :exclusions [org.clojure/tools.reader]]
                 [ring/ring-core "1.9.4"]
                 [ring/ring-jetty-adapter "1.9.4"]
                 [ring-jetty-component "0.3.1"
                  :exclusions [org.clojure/tools.reader
                               ring/ring-core]]
                 [digest "1.4.10"]
                 [clj-http "3.12.3"
                  :exclusions [commons-codec
                               commons-io]]
                 [aero "1.1.6"]
                 [one-time "0.7.0"
                  :exclusions [commons-codec]]

                 ;; logging
                 [org.clojure/tools.logging "1.2.3"]
                 [ch.qos.logback/logback-classic "1.3.0-alpha5"
                  :exclusions [com.sun.mail/javax.mail]]
                 
                 ;; AWS
                 [com.cognitect.aws/api "0.8.539"
                  :exclusions [org.eclipse.jetty/jetty-util]]
                 [com.cognitect.aws/endpoints "1.1.12.129"]
                 [com.cognitect.aws/s3 "814.2.991.0"]]
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
                   :dependencies [[reloaded.repl "0.2.4"]
                                  [clj-commons/pomegranate "1.2.1"
                                   :exclusions
                                   [commons-logging
                                    org.apache.httpcomponents/httpcore]]
                                  [org.clojure/tools.namespace "1.2.0"]
                                  [eftest "0.5.9"]
                                  [kerodon "0.9.1"
                                   :exclusions [clj-time
                                                org.apache.httpcomponents/httpcore
                                                org.flatland/ordered
                                                ring/ring-codec]]
                                  [net.polyc0l0r/bote "0.1.0"
                                   :exclusions [commons-codec
                                                javax.mail/mail
                                                org.clojars.kjw/slf4j-simple]]
                                  [nubank/matcher-combinators "3.3.1"]]
                   :resource-paths ["local-resources"]}
   :project/test  {}})
