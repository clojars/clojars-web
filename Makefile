.PHONY: check
check:
	clojure -M:defaults:check

.PHONY: lint
lint:
	./bin/clj-kondo --lint src test

.PHONY: migrate-db
migrate-db:
	clojure -M:defaults:migrate-db

.PHONY: nvd-check
nvd-check:
	./bin/nvd-check

.PHONY: prep-deps
prep-deps:
	clojure -A:defaults -X:deps prep

.PHONY: test
test:
	./bin/kaocha

.PHONY: uberjar
uberjar:
	clojure -T:build uberjar
