(ns clojars.friend.oauth.github
  (:require
   [clojars.friend.oauth :as oauth]))

(defn workflow [oauth-service http-service db]
  (fn [req]
    (case (:uri req)
      "/oauth/github/authorize" (oauth/authorize oauth-service)
      "/oauth/github/callback" (oauth/callback req oauth-service http-service db)
      nil)))
