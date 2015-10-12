{:port 8080
 :bind "0.0.0.0"
 :nailgun-bind "127.0.0.1"
 :db {:classname "org.sqlite.JDBC"
      :subprotocol "sqlite"
      :subname "data/dev_db"}
 :base-url "http://localhost:8080"
 :repo "data/dev_repo"
 :deletion-backup-dir "data/dev_deleted_items"
 :bcrypt-work-factor 12
 :mail {:hostname "localhost"
        :from "noreply@clojars.org"
        :ssl false}
 :nrepl-port 7991}
