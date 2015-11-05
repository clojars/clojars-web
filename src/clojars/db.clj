(ns clojars.db)

(defprotocol Database
  (find-user [this username])
  (find-user-by-user-or-email [this username-or-email])
  (find-user-by-password-reset-code [this reset-code time])
  (jars-by-username [this username])

  (add-user [this email username password pgp-key time])
  (update-user [this account email username password pgp-key])
  (update-user-password [this reset-code password])
  (set-password-reset-code! [this username-or-email reset-code time])

  (find-groupnames [this username])
  (group-membernames [this groupname])
  (group-keys [this groupname])
  (jars-by-groupname [this groupname])
  (add-member [this groupname username added-by])
  ;; does not delete jars in the group. should it?
  (delete-groups [this group-id])

  (recent-versions
    [this groupname jarname]
    [this groupname jarname num])
  (count-versions [this groupname jarname])
  (recent-jars [this])
  (jar-exists [this groupname jarname])
  (find-jar
    [this groupname jarname]
    [this groupname jarname verison])
  (all-projects [this offset limit])
  (count-all-projects [this])
  (count-projects-before [this name])

  (find-jars-information
    [this group-id]
    [this group-id artifact-id])
  (promote [this group name version time])
  (promoted? [this group-id artifact-id version])
  
  (add-jar [this account {:keys [group name version
                                 description homepage authors] :as jarmap} time])
  (delete-jars
    [this group-id]
    [this group-id jar-id]
    [this group-id jar-id version]))
