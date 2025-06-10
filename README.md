Clojars web interface
=====================

[![CircleCI](https://circleci.com/gh/clojars/clojars-web.svg?style=svg)](https://circleci.com/gh/clojars/clojars-web)
[![Dependencies Status](https://versions.deps.co/clojars/clojars-web/status.svg)](https://versions.deps.co/clojars/clojars-web)

This is the source code for the [Clojars](https://clojars.org/) jar
repository webapp.

If you're looking for user documentation, try
the [wiki](http://github.com/clojars/clojars-web/wiki/_pages). There is a
also a [FAQ](https://github.com/clojars/clojars-web/wiki/About).

See [the CHANGELOG](CHANGELOG.org) for changes.

Contributing
------------

Please report bugs/feature requests as an [issue on this
repository](https://github.com/clojars/clojars-web/issues/new/choose). For
issues with the repository or group name verification, please file an [issue on
the administration repository](https://github.com/clojars/administration/issues/new/choose).

If you'd like contribute a change please send a GitHub pull request for a topic
branch. Feel free to open a draft pull request early with a in #clojars on the
[Clojurians slack](https://clojurians.slack.com/messages) to get feedback from
other contributors. If you are looking for a task to work on, take a look at
issues labeled
[ready-for-work](https://github.com/clojars/clojars-web/labels/ready-for-work).

We try to make releases fairly soon after merging contributions, but ping us if
it has been a week or two and you'd like something pushed to the production
website.

Development
-----------

**Note: Java 21 and `make` are required**

### Development system

To begin developing, start with a REPL (Note: if you are instead starting a repl from your editor, you will need to include the `:dev` alias).

```sh
make repl
```

You'll need elasticmq, minio, and postgres running as well. That's managed via
docker-compose:

```sh
docker-compose up
```

Run `migrate` to initialize the database to the latest migration.

```clojure
user=> (migrate)
...
```

or alternatively, from the command line.

```sh
$ make migrate-db
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
mkdir data/dev_repo
cp -r ~/.m2/repository/* data/dev_repo
make setup-dev-repo
```

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

Tests can also be run via [kaocha](https://github.com/lambdaisland/kaocha):

```sh
make test
```

Deployment
----------

See the [Deployment instructions](https://github.com/clojars/infrastructure#deployment) in the 
[infrastructure repo](https://github.com/clojars/infrastructure).

Also see [Configuration](#configuration).

Configuration
-------------

The configuration is loaded from `resources/config.edn`.

When running automated tests at the repl, or with `make test`, a test environment
is used to provide isolation. It can be found in `test/clojars/test/test_helper.clj`.

License
-------

Copyright © 2009-2023 Alex Osborne, Phil Hagelberg, Nelson Morris,
Toby Crawley, Daniel Compton and
[contributors](https://github.com/clojars/clojars-web/graphs/contributors).

Distributed under the Eclipse Public License, the same as Clojure. See the file COPYING.
