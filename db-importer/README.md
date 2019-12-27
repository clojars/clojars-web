## DB Importer

This is a tool for moving between sqlite and postgres. It is intended
to be used for migrating the production data, but can also be used to
migrate a dev db.

### Usage

```sh
clj -m clojars.db-import path-to-sqlite-db pg-host pg-port pg-user pg-password
```

As an example, here is how you could import a local dev sqlite db in
to the dev postgres db running in docker (via `../docker-compose`):

```sh
clj -m clojars.db-import ../data/db localhost 55432 clojars clojars
```
