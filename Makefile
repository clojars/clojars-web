.PHONY: check
check:
	clojure -M:check

.PHONY: lint
lint:
	./bin/lint

.PHONY: migrate-db
migrate-db:
	clojure -M:migrate-db

.PHONY: nvd-check
nvd-check:
	./bin/nvd-check

.PHONY: prep-deps
prep-deps:
	clojure -X:deps prep

.PHONY: repl
repl:
	clj -A:dev

.PHONY: setup-dev-repo
setup-dev-repo:
	clojure -M:setup-dev-repo

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

.PHONY: sync-dependabot
sync-dependabot:
	./bin/sync-dependabot

.PHONY: check-dependabot
check-dependabot:
	./bin/check-dependabot
