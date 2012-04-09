{:db {:classname "org.sqlite.JDBC"
      :subprotocol "sqlite"
      :subname "data/dev_db"}
 :key-file "data/dev_authorized_keys"
 :repo "data/dev_repo"
 :bcrypt-work-factor 12
 :mail {:hostname "smtp.gmail.com"
        :from "noreply@clojars.org"
        :username "clojars@pupeno.com"
        :password "fuuuuuu"
        :port 465 ; If you change ssl to false, the port might not be effective, search for .setSSL and .setSslSmtpPort
        :ssl true}}
