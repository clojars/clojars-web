;;; Pallet project configuration file for clojars
(require
 '[pallet.crate.git :refer [git clone]]
 '[pallet.crate.java :refer [java]]
 '[pallet.crate.lein :refer [lein leiningen]])


(def repo "git://github.com/ato/clojars-web.git")

(defplan setup-machine
  []
  (packages
   :apt ["sqlite3"]
   :aptitude ["sqlite3"]
   :brew ["sqlite"]))

(defplan clone-repo
  []
  (with-action-options {:script-prefix :no-sudo}
    (remote-directory ".ssh" :mode "0755")
    (remote-file
     ".ssh/config"
     :content "Host github.com\n\tStrictHostKeyChecking no\n"
     :no-versioning true) ; pallet beta.1 workaround
    (clone repo)))

(defplan migrate-db
  []
  (with-action-options {:script-prefix :no-sudo :script-dir "clojars-web"}
    (lein "migrate")))

(defplan import-test-data
  []
  (with-action-options {:script-prefix :no-sudo :script-dir "clojars-web"}
    (exec-checked-script
     "Import Test Data"
     ("wget" "http://meshy.org/~ato/clojars-test-data.sql.gz")
     ("mkdir" -p data)
     ("rm" -f "data/dev_db")
     (pipe ("gunzip" -c "clojars-test-data.sql.gz")
           ("sqlite3" "data/dev_db")))))

(defplan run
  []
  (with-action-options {:script-prefix :no-sudo :script-dir "clojars-web"}
    (exec-checked-script
     "Run clojars"
     ("(" nohup ("lein" run) "&" ")")
     ("sleep" 5)))) ; let nohup detach

(defproject clojars
  :provider {:vmfest
             {:node-spec
              {:image {:os-family :ubuntu :os-version-matches "12.04"
                       :os-64-bit true}}
              :group-suffix "u1204"
              :selectors #{:default}}}

  :groups [(group-spec "clojars"
                       :extends [with-automated-admin-user
                                 (java {}) (leiningen {}) (git {})]
                       :phases {:configure (plan-fn
                                             (setup-machine)
                                             (clone-repo)
                                             (migrate-db)
                                             (import-test-data)
                                             (run))
                                :import-test-data (plan-fn
                                                    (import-test-data))})])
