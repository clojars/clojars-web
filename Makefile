.DEFAULT_GOAL := help

.PHONY: help
help:
	@echo "Available tasks:"
	@echo "  antq            Check for outdated dependencies"
	@echo "  check           Check for reflection warnings"
	@echo "  lint            Run linter"
	@echo "  migrate-db      Run database migrations"
	@echo "  nvd-check       Check dependencies for CVEs"
	@echo "  prep-deps       Prepare dependencies"
	@echo "  repl            Start a development REPL"
	@echo "  setup-dev-repo  Set up local dev repository"
	@echo "  tag-release     Tag a release"
	@echo "  test            Run tests"
	@echo "  typos           Check for typos"
	@echo "  uberjar         Build an uberjar"

.PHONY: antq
antq:
	./bin/antq

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

