{:port 8080
 :bind "0.0.0.0"
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
 ;; :raven-dsn "https://b70a31b3510c4cf793964a185cfe1fd0:b7d80b520139450f903720eb7991bf3d@sentry.example.com/1"
 :nrepl-port 7991}
