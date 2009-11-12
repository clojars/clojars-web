
(compile 'clojars.scp)

(require 'clojars.scp)

(import 'com.martiansoftware.nailgun.NGServer
        'java.net.InetAddress)


(use 'clojars.web
     'compojure)

(require 'swank.swank)


(swank.swank/start-server "/dev/null" :port 4005)

(run-server {:port 8000}
                     "/*" (servlet clojars-app))

(println "starting nailgun")

(.run (NGServer. (InetAddress/getLocalHost) 8701))

(println "done")
