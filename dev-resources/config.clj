{:port 8080
 :bind "0.0.0.0"
 :nailgun-bind "127.0.0.1"
 :db {:classname "org.sqlite.JDBC"
      :subprotocol "sqlite"
      :subname "data/dev_db"}
 :key-file "data/dev_authorized_keys"
 :repo "data/dev_repo"
 :bcrypt-work-factor 12
 :mail {:hostname "localhost"
        :from "noreply@clojars.org"
        :ssl false}}
