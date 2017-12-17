Clojars web interface
=====================

[![CircleCI](https://circleci.com/gh/clojars/clojars-web.svg?style=svg)](https://circleci.com/gh/clojars/clojars-web)
[![Dependencies Status](https://versions.deps.co/clojars/clojars-web/status.svg)](https://versions.deps.co/clojars/clojars-web)

This is the source code for the [Clojars](https://clojars.org/) jar
repository webapp.

If you're looking for user documentation, try
the [wiki](http://github.com/ato/clojars-web/wiki/_pages). There is a
also a [FAQ](https://github.com/ato/clojars-web/wiki/About).

See [release announcements on the mailing list](https://groups.google.com/forum/?fromgroups#!topicsearchin/clojars-maintainers/group:clojars-maintainers$20AND$20subject:ann) for recent user-facing changes.

Contributing
------------

Please report bugs or problems with the repository on the
[bug tracker](https://github.com/ato/clojars-web/issues).

Design discussions occur on the
[clojars-maintainers list](http://groups.google.com/group/clojars-maintainers)
and the #leiningen channel on irc.freenode.org.

If you'd like contribute a change please send a GitHub pull request
for a topic branch.  Feel free to open a pull request early with a
"not ready for merging" note or ask on the mailing list or IRC to get
feedback from other contributors. If you are looking for a task to
work on, take a look at issues labeled
[ready](https://github.com/ato/clojars-web/labels/ready).

We try to make releases fairly soon after merging contributions,
but post to the mailing list if it's been a week or two and you'd like
something pushed to the production website.

Development
-----------

**Note: Java 8+ is required**

### Development system

To begin developing, start with a REPL.

```sh
lein repl
```

Run `migrate` to initialize the database to the latest migration.

```clojure
user=> (migrate)
...
```

or alternatively, from the command line.

```sh
$ lein run -m user/migrate
```

Run `go` to initiate and start the system.

```clojure
user=> (go)
:started
```

By default this creates a running development system at <http://localhost:8080>.

**Note:** You may get the following error in the browser when accessing the dev
system after running `(go)` for the first time:

    No implementation of method: :-report-error of protocol: #'clojars.errors/ErrorReporter found for class: clojars.errors.StdOutReporter

If so, running `(go)` a second time should eliminate the error.

When you make changes to your source files, use `reset` to reload any
modified files and reset the server.

```clojure
user=> (reset)
:reloading (...)
:resumed
```

If you'd like to hack on the UI or search it might be useful to have
production-like metadata. To create that, use
`clojars.tools.setup-dev/-main` to create test users, import an existing
maven repository (your local `~/.m2/repository/` works well), and
setup a search index:

```sh
cp -r ~/.m2/repository data/dev_repo
lein run -m clojars.tools.setup-dev
```
If you want to use the actual repo from clojars.org, you can grab it
via [rsync](http://github.com/ato/clojars-web/wiki/Data).

Note that this setup task isn't perfect - SNAPSHOTS won't have
version-specific metadata (which won't matter for the operation of
clojars, but may matter if you try to use the resulting repo as a real
repo), and versions will be listed out of order on the project pages,
but it should be good enough to test with.

### Testing

Testing is designed to work in a REPL to allow flow.

```clojure
user=> (test)
...
```

```clojure
user=> (test #'clojars.test.unit.db/added-users-can-be-found)
...
```

Tests can also be run through Leiningen for CI.

```sh
lein test
```

### Production system with development config

Occasionally it can be useful to start a production system based on the development
configuration. This can be done by `lein run`.

Deployment
----------

Also see [Configuration](#configuration).

1. Compile with: `lein uberjar`
1. Deploy `target/uberjar/clojars-web-*-standalone.jar` to the server
1. Run the migrations `java -cp clojars-web-*-standalone.jar clojure.main -m clojars.tools.migrate-db`
1. Run the production system: `java -jar clojars-web-*-standalone.jar`

Configuration
-------------

The default configuration is loaded from
`resources/default_config.edn`. To override values from the default
config, place them in map in an edn file, and specify the path to that
file via the `clojars.config.file` system property.

When running automated tests at the repl, or with `lein test`, a test environment
is used to provide isolation. It can be found in `test/clojars/test/test_helper.clj`.

License
-------

Copyright © 2009-2017 Alex Osborne, Phil Hagelberg, Nelson Morris,
Toby Crawley, Daniel Compton and
[contributors](https://github.com/ato/clojars-web/graphs/contributors).

Distributed under the Eclipse Public License, the same as Clojure. See the file COPYING.
