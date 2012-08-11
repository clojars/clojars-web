Clojars.org Sysadmin Guide
==========================

This describes the production deployment on clojars.org.  It's aimed
at the clojars.org maintainers.  You might find it interesting if
you're trying to run your own repository though.

Architecture
------------

![diagram](https://raw.github.com/ato/clojars-web/master/architecture.png)

There's a user "clojars" which runs all the clojars webapp bits.  The
repository lives in `/home/clojars/repo`.

We always run two instances of the webapp: `clojars` and
`clojars-backup`.  They're started by Upstart as defined in:

    /etc/init/clojars.conf         http://clojars.org:8001/
    /etc/init/clojars-backup.conf  http://clojars.org:8002/

The nginx configuration will try to direct all requests to `clojars` and
only if it's down use `clojars-backup`.  There's a basic TCP load
balancer "balance" which does the same thing for the nailgun port for
the scp access.

The reason for running two instances is:

1. It allows us to have outage-free deploys (sessions aren't persisted
   at the moment so logged in users will get kicked out though).

2. When deploying, `clojars-backup` is deployed first so that you can do
   a last minute sanity test on http://clojars.org:8002/ that the 
   release built correctly and is working in the production environment.

3. Failover in case anything goes wrong with the primary instance. I'm
   in the habit of this from badly-behaved Java applications with
   massive GC pauses, deadlocks etc but Clojars tends to be pretty
   stable.

Releasing
---------

As the server's MOTD says, the release process is:

    sudo -iu clojars deploy-clojars $GIT_TAG

That's just a simple shell script `~clojars/bin/deploy-clojars` which:

1. Checks out the given tag into a fresh directory.'
2. Runs the tests and ensures they pass.
3. Creates a release jar with `lein uberjar`
4. Copies the uberjar into `~clojars/releases`
5. Updates the symlink `~clojars/releases/clojars-web-current.jar`
6. Restarts `clojars-backup`

It will then ask you to do any last minute testing and then you can
run "sudo restart clojars" yourself to update the primary webapp
instance.

Rollback
--------

1. Point `clojars-web-current.jar` symlink back to the previous
   version:

   sudo -u clojars ln -sf clojars-web-0.8.1-standalone.jar ~clojars/releases/clojars-web-current.jar

2. Restart the backup instance.

   sudo restart clojars-backup

3. Wait until clojars-backup has started up to avoid outages.

4. Restart the primary instance (if you need to):

   sudo restart clojars
