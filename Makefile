.PHONY: check
check:
	clojure -M:defaults:check

.PHONY: lint
lint:
	./bin/clj-kondo --lint src test

.PHONY: nvd-check
nvd-check:
	./bin/nvd-check

.PHONY: test
test:
	./bin/kaocha
