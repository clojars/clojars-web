(ns clojars.friend.oauth.gitlab
  (:require
   [clojars.friend.oauth :as oauth]))

(defn workflow [oauth-service http-service db]
  (fn [req]
    (case (:uri req)
      "/oauth/gitlab/authorize" (oauth/authorize oauth-service)
      "/oauth/gitlab/callback" (oauth/callback req oauth-service http-service db)
      nil)))
