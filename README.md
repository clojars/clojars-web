Clojars web interface
=====================

This is the source code for the [Clojars](http://clojars.org/) jar
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

Running the webapp
------------------

There are several ways to run Clojars depending on what you intend to do with
it. Regardless of how you run it, you first need to do some setup:

1. Install [Leiningen](http://github.com/technomancy/leiningen)
   * Mac OS X Homebrew: `brew install leiningen`

2. Run the DB migrations: `lein migrate`

To run the application using Leinigen 2:

1. Run the webapp: `lein run` (see `--help` for options)

2. Now try hitting [localhost:8080](http://localhost:8080) in your web browser.

To build a standalone jar for deploying to a server:

1. Compile with: `lein uberjar`

2. Run the webapp: `java -jar target/clojars-web-*-standalone.jar`

To run the application in auto-reload mode, from the console:

1. Run `lein ring server`

and that's it, it should automatically open a browser in [localhost:3000](http://localhost:3000).

If you'd like to run it out of an editor/IDE environment you can
probably eval a call to the `-main` function in
`src/clojars/main.clj`.

Configuration
-------------

All options are available as command-line switches.  Additionally some
can be set using environment variables.  See `lein run -h` for the
full list.

Options may be read from a file using the `-f` switch, setting the
`CONFIG_FILE` environment variable or by putting a file named
`config.clj` on the classpath.  The config file should be a bare
Clojure map:

    {:db {:classname "org.sqlite.JDBC"
          :subprotocol "sqlite"
          :subname "data/dev_db"}
     :repo "data/dev_repo"
     :bcrypt-work-factor 12
     :mail {:hostname "localhost"
            :from "noreply@clojars.org"
            :ssl false}}

The classpath option can be used with Leiningen 2 profiles.  When
running out of a source checkout using `lein run` the configuration
will be read from `dev-resources/config.clj`.  When running automated
tests with `lein test` then `test-resources/config.clj` is used.


Test data
---------

If you'd like to hack on the UI or search it might be useful to have
production-like metadata. To create that, use
`clojars.dev.setup/-main` to create test users, import an existing
maven repository (your local `~/.m2/repository/` works well), and
setup a search index:

    $ cp -r ~/.m2/repository data/dev_repo
    $ lein run -m clojars.dev.setup

If you want to use the actual repo from clojars.org, you can grab it
via [rsync](http://github.com/ato/clojars-web/wiki/Data).

Note that this setup task isn't perfect - SNAPSHOTS won't have
version-specific metadata (which won't matter for the operation of
clojars, but may matter if you try to use the resulting repo as a real
repo), and versions will be listed out of order on the project pages,
but it should be good enough to test with.


License
-------

Copyright © 2009-2015 Alex Osborne, Phil Hagelberg, Nelson Morris,
Toby Crawley and
[contributors](https://github.com/ato/clojars-web/graphs/contributors).

Distributed under the Eclipse Public License, the same as Clojure. See the file COPYING.
