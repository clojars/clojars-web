.PHONY: check
check:
	clojure -M:defaults:check

.PHONY: lint
lint:
	./bin/lint

.PHONY: migrate-db
migrate-db:
	clojure -M:defaults:migrate-db

.PHONY: nvd-check
nvd-check:
	./bin/nvd-check

.PHONY: prep-deps
prep-deps:
	clojure -A:defaults -X:deps prep

.PHONY: repl
repl:
	clj -A:defaults:dev

.PHONY: setup-dev-repo
setup-dev-repo:
	clojure -M:defaults:setup-dev-repo

.PHONY: tag-release
tag-release:
	clojure -T:build tag-release

.PHONY: test
test:
	./bin/kaocha

.PHONY: typos
typos:
	./bin/typos

.PHONY: uberjar
uberjar:
	clojure -T:build uberjar
