# Clojars.org Sysadmin Guide

This describes the production deployment on clojars.org.  It's aimed
at the clojars.org maintainers. 

## Architecture & Deployment 

See https://github.com/clojars/clojars-server-config/#clojars-server-config.


## Group Verification 

Verification requests come in as [issues in the administration repo][admin-repo],
and requesters need to add a `TXT` record for the domain that
corresponds to the group.

There is a `check-and-verify-group!` function in the
[`clojars.admin`][check-ns] namespace that will verify the `TXT`
record and ask for confirmation before marking the group as
verified. An example of usage:

```
clojars.advmin> (check-and-verify-group! "com.example" "example.com")
TXT records for example.com:
["clojars someuser"
 "other txt record"]
Found username someuser
Group exists. Active members:
("someuser" "someotheruser"]
User is an active member of the group.
Do you want to verify com.example for someuser? [y/N] y
Group verified: https://clojars.org/groups/com.example
```

In order to use this function, you will need a connection to the
production db. The most straightforward way to do that is to ssh in to
the production instance then connect to the REPL running within the
server process at port `7991`.

[admin-repo]: https://github.com/clojars/administration/issues?q=is%3Aissue+is%3Aopen+sort%3Aupdated-desc
[admin-ns]: src/clojars/admin.clj
