(require '[circleci.test.report :refer (clojure-test-reporter)])
(require '[circleci.test.report.junit :as junit])

{:selectors {:all (constantly true)
             :default (complement :disabled)}
 :test-results-dir (or (System/getenv "CIRCLE_TEST_REPORTS")
                       "target/test-results")
 :reporters [clojure-test-reporter junit/reporter]}
